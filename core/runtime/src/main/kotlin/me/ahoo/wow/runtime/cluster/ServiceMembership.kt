/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.runtime.cluster

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.health.launchLoop
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * One node's membership in its [service] on one lease store: a single record, heartbeated every
 * [ClusterOptions.heartbeatInterval], listing the lease groups the node takes part in, and a single read of the
 * service's live members every [ClusterOptions.reconcileInterval] (or [ClusterOptions.routeRefreshInterval], the
 * shorter), which every [PartitionOwnership] of the node reuses for its reconcile and its routes instead of reading
 * its own. The store operations of membership so stay constant in the number of lease groups: a heartbeat and a read
 * per node and interval, where each group used to heartbeat, and read its members twice, on its own.
 *
 * An ownership [join]s with its group and [leave]s it; the record lists the groups joined and not [stuck]. A change
 * of that list is heartbeated at once, so the other nodes see a group left (a graceful stop, a failed start, a stuck
 * reconcile) as soon as they saw it before, when each group had its own record: HRW still counts, per group, the
 * members that list it. The membership starts with its first group and leaves the service, at once,
 * with its last.
 *
 * A loop of the membership that ends on an error no round catches (`NodeLoops.kt`) is not restarted, and its end ends
 * the node's part in the service on this store: without heartbeats the other nodes no longer count it, and without
 * reads it no longer sees who serves a group. The membership then calls the store no more for what ended (the record
 * expires on its own, the members stay as last read), joins no group, and tells each group it serves ([whenEnded]),
 * which steps out, rather than renew a share no other node can reach or take.
 */
@InternalWowApi
class ServiceMembership(
    val service: String,
    val node: String,
    /** This node's internal address, read at each heartbeat: a node may learn it once its port is bound. */
    private val addressOf: () -> String,
    private val leases: LeaseStore,
    private val options: ClusterOptions = ClusterOptions(),
    private val context: CoroutineContext = Dispatchers.Default,
    /** Times the [acquisitions] gate's windows; a test passes its virtual time. */
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    /**
     * This node's acquisitions on the store, held back while it fails (#6675): every lease group of the node on the store
     * shares it, and the heartbeats tell it when the store fails and when it answers again.
     */
    internal val acquisitions =
        AcquisitionGate(node, service, options.reconcileInterval, options.leaseTtl, timeSource)

    private val lock = Mutex()
    private val joined = ConcurrentHashMap<String, () -> Unit>()
    private val stuckGroups = ConcurrentHashMap.newKeySet<String>()
    private val reads = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var running: Job? = null

    /** The groups the record lists last heartbeated; null while the node is not a member. */
    @Volatile
    private var listed: Set<String>? = null

    /** Why the membership ended: the first of its loops that did; not completed while both run. */
    private val ended = CompletableDeferred<String>()

    /** Whether the heartbeats ended: the record is written no more, and expires on its own. */
    @Volatile
    private var heartbeatsEnded = false

    /** Whether the reads of the members ended: [members] stays as last read. */
    @Volatile
    private var readsEnded = false

    /** The service's live members as last read, each with the groups it serves. */
    @Volatile
    var members: List<Member> = emptyList()
        private set

    /** The live members of [group] as last read: those whose record lists it. */
    fun membersOf(group: String): List<Member> = members.filter { group in it.groups }

    /**
     * Takes part in [group]: heartbeats the record with it and reads the members at once, then calls [read] after each
     * read of the members until [leave]. The first group starts the heartbeats and the reads.
     */
    suspend fun join(group: String, read: () -> Unit): Unit = lock.withLock {
        check(!ended.isCompleted) { "$node takes part in $service no more: ${ended.getCompleted()}." }
        check(joined.putIfAbsent(group, read) == null) { "$node already takes part in $group." }
        reads += read
        try {
            heartbeat()
            refresh()
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever failed, the join is undone before it is thrown again.
            failure: Exception,
        ) {
            // Undone, so that a start that fails leaves no record of a group it does not take part in.
            joined.remove(group)
            reads -= read
            runCatching { if (joined.isEmpty()) leaveService() else heartbeat() }
                .onFailure { failure.addSuppressed(it) }
            throw failure
        }
        if (running == null) {
            running = CoroutineScope(context + SupervisorJob()).launch {
                watch(every(options.heartbeatInterval, "heartbeat") { lock.withLock { heartbeat() } }, HEARTBEATS_ENDED)
                val reading = minOf(options.reconcileInterval, options.routeRefreshInterval)
                watch(every(reading, "read the members") { refresh() }, READS_ENDED)
            }
        }
    }

    /**
     * Stops taking part in [group]: heartbeats the record without it at once, or, for the last group, leaves the
     * service and stops the heartbeats and the reads. The group leaves the record's list before the store is called, so
     * no heartbeat lists it again, even one that waits on the store meanwhile; a leave of a group left already, one
     * whose call was cancelled say, still brings the record and the heartbeats in line.
     */
    suspend fun leave(group: String) {
        joined.remove(group)?.let { reads -= it }
        stuckGroups -= group
        lock.withLock { heartbeatWithout() }
    }

    /** After a [leave]: the record without the groups left, or, with none joined, no record nor heartbeat. */
    private suspend fun heartbeatWithout() {
        if (joined.isEmpty()) {
            running?.cancelAndJoin()
            running = null
            leaveService()
        } else {
            heartbeat()
        }
    }

    /**
     * Stops taking part in [group] as a crash would, for tests of failures: the record is not heartbeated again
     * without it, and, for the last group, the heartbeats and reads stop without leaving, so the record expires.
     */
    internal suspend fun abort(group: String): Unit = lock.withLock {
        val read = joined.remove(group) ?: return@withLock
        reads -= read
        stuckGroups -= group
        if (joined.isEmpty()) {
            running?.cancelAndJoin()
            running = null
            listed = null
        }
    }

    /**
     * Marks [group] stuck or not: a node whose reconcile of a group is stuck leaves that group's members, so the others
     * take its partitions, and lists it again once the reconcile ends. Heartbeated at once.
     */
    suspend fun stuck(group: String, stuck: Boolean): Unit = lock.withLock {
        val changed = if (stuck) stuckGroups.add(group) else stuckGroups.remove(group)
        if (changed && joined.containsKey(group)) {
            heartbeat()
        }
    }

    /**
     * Runs [action] in [scope] once the membership ended (see the class description), with why: the first of its loops
     * that ended. Not run when [scope] stops first.
     */
    fun whenEnded(scope: CoroutineScope, action: suspend (String) -> Unit): Job = scope.launch { action(ended.await()) }

    /** Reads the service's members, unless the reads ended, and calls each group's [join] callback. */
    suspend fun refresh() {
        if (!readsEnded) {
            members = leases.members(service)
        }
        reads.forEach { it() }
    }

    /** Removes the record, unless the heartbeats ended: it then expires on its own. */
    private suspend fun leaveService() {
        listed = null
        if (!heartbeatsEnded) {
            leases.leave(service, node)
        }
    }

    /** Heartbeats the record with the groups joined and not stuck; leaves the service while there are none. */
    private suspend fun heartbeat() {
        if (heartbeatsEnded) {
            return
        }
        val groups = joined.keys - stuckGroups
        if (groups.isEmpty() && joined.isNotEmpty()) {
            if (listed != null) {
                leases.leave(service, node)
                listed = null
            }
            return
        }
        acquisitions.recorded("a heartbeat") { leases.heartbeat(service, node, addressOf(), options.memberTtl, groups) }
        listed = groups
    }

    /**
     * Watches [loop]: one that ends while the membership runs ended on an error no round catches, and the membership
     * ends with [why]. A leave that stops the loops cancels the watch with them.
     */
    private fun CoroutineScope.watch(loop: Job, why: String) = launch {
        loop.join()
        when (why) {
            HEARTBEATS_ENDED -> heartbeatsEnded = true
            else -> readsEnded = true
        }
        logger.error {
            "$node's membership in $service ended ($why), and nothing restarts it: the node steps out of " +
                "${joined.keys}, so the other nodes take their partitions over."
        }
        ended.complete(why)
    }

    private fun CoroutineScope.every(interval: kotlin.time.Duration, action: String, block: suspend () -> Unit) =
        launchLoop(action) {
            delay(interval)
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                logger.warn(failure) { "$node failed to $action in $service; retrying on the next round." }
            }
        }

    private companion object {
        val logger = KotlinLogging.logger {}

        /** Why the membership ends when its heartbeats did. */
        const val HEARTBEATS_ENDED = "its membership heartbeats ended"

        /** Why the membership ends when its reads of the members did. */
        const val READS_ENDED = "its reads of the members ended"
    }
}
