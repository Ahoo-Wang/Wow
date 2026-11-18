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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.runtime.log.HandoffOptions
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.subscription.Member
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The routes of one lease group read again before a command whose route names no owner runs without the lease:
 * while a partition changes hands, a node's routes, read every [ClusterOptions.routeRefreshInterval], may not show
 * the owner that acquired it a moment ago. A command it ran without the lease then commits above the new owner's
 * block of sequences, which that owner's tail reaches only at its next read of the store. So once, before running it
 * so, the node [read]s the routes and sends it to the owner they name now ([target]).
 *
 * - **Bounded**: one read at a time (the others wait for it and take what it read), at most one each [interval], each
 *   within [timeout]. After a read that failed or timed out, none until the routes are read again by the ownership's
 *   own refresh ([routes] then holds another table): a lease store that cannot be read costs one [timeout] and then
 *   nothing until it can be read again, the commands running without the lease as before.
 * - **Off the leased path**: only a command with no owner to send it to asks ([ownerOf]); one whose partition this
 *   node holds, or whose owner the routes name, never does.
 *
 * [reads] and [forwards] count what it did: the reads it made, and the commands it found an owner for.
 */
internal class RouteLookup(
    private val group: String,
    /** Reads the group's routes again; the next [target] answers from them. */
    private val read: suspend () -> Unit,
    /** The owner the routes name for a partition, null for none ([PartitionOwnership.forwardTarget]). */
    private val target: (Int) -> Member?,
    /** The routes as last read ([PartitionOwnership.routes]): a refresh that succeeds replaces the table. */
    private val routes: () -> RouteTable,
    private val interval: Duration = INTERVAL,
    private val timeout: Duration = TIMEOUT,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val reading = Mutex()

    /** When the last read that succeeded ended. */
    @Volatile
    private var last: TimeMark? = null

    /** The routes as they were when a read failed, until a refresh replaces them: no read meanwhile. */
    @Volatile
    private var failedOn: RouteTable? = null

    private val readCount = AtomicLong()
    private val forwardCount = AtomicLong()

    /** The reads of the routes made. */
    val reads: Long get() = readCount.get()

    /** The commands sent to an owner a read named, in place of running without the lease. */
    val forwards: Long get() = forwardCount.get()

    /** What one command's lookup did. */
    class Outcome(
        /** The owner to send it to, null to run it here without the lease. */
        val owner: Member?,
        /** Whether this command made the read; false when it took another's, or none was made. */
        val read: Boolean,
        /** Whether the read it made, or the one it took, failed or timed out. */
        val failed: Boolean,
    ) {
        /** What it found, as observed. */
        val observed: ObservedEvent.RouteLookedUp.Outcome
            get() = when {
                owner != null -> ObservedEvent.RouteLookedUp.Outcome.OWNER
                failed -> ObservedEvent.RouteLookedUp.Outcome.FAILED
                else -> ObservedEvent.RouteLookedUp.Outcome.NONE
            }
    }

    /**
     * The owner of [partition] once the routes are read again, at most once each [interval] and not after a failed
     * read until a refresh succeeded; null to run the command here without the lease, as before.
     */
    suspend fun ownerOf(partition: Int): Outcome {
        if (failing()) {
            return Outcome(null, read = false, failed = true)
        }
        val made = reading.withLock {
            // The read another command made while this one waited for the lock, or one a moment ago, answers it.
            if (failing() || last?.let { it.elapsedNow() < interval } == true) {
                return@withLock false
            }
            readCount.incrementAndGet()
            val ok = try {
                withTimeoutOrNull(timeout) { read() } != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // Whatever the lease store throws, the command runs without the lease, as it would have.
                logger.debug(failure) {
                    "Reading the routes of $group before running a command without the lease failed."
                }
                false
            }
            if (ok) {
                last = timeSource.markNow()
                failedOn = null
            } else {
                failedOn = routes()
            }
            true
        }
        val failed = failing()
        val owner = if (failed) null else target(partition)
        if (owner != null) {
            forwardCount.incrementAndGet()
        }
        return Outcome(owner, made, failed)
    }

    /** Whether a read failed and no refresh of the routes has succeeded since: none is tried meanwhile. */
    private fun failing(): Boolean {
        val failed = failedOn ?: return false
        if (routes() !== failed) {
            failedOn = null
            return false
        }
        return true
    }

    internal companion object {
        /**
         * At most one read of a group's routes in this long: the reads of a move come from every command it would run
         * without the lease, and one read answers them all; a new owner acquires within a reconcile round, so a read
         * every tenth of a second sees it within that much.
         */
        val INTERVAL: Duration = 100.milliseconds

        /**
         * The longest one read may take: a lease read is a few milliseconds, and a command that would have run at once
         * waits for it, so a store that answers slower than this is taken as unreadable.
         */
        val TIMEOUT: Duration = 200.milliseconds

        private val logger = KotlinLogging.logger {}
    }
}

/**
 * These hand-off options with a tail's probes on schedule after it opens ([HandoffOptions.scheduledProbes]), on a node
 * of [cluster]: another node appends without the lease in a partition this node acquired only while its routes do not
 * name this one, so for at most a route refresh ([ClusterOptions.routeRefreshInterval]) after the acquisition, the
 * reuse of a lookup's read ([RouteLookup.INTERVAL]) and a command already deciding, which one probe interval covers.
 * With the defaults that is 2.1 s: one or two reads more per partition acquired, when its own commits keep it from an
 * idle probe, and none on a partition held longer. Unchanged without a cluster: every command runs where it arrives.
 */
internal fun HandoffOptions.probingAfterAcquiring(cluster: ClusterOptions?): HandoffOptions {
    cluster ?: return this
    val window = cluster.routeRefreshInterval + RouteLookup.INTERVAL + probeInterval
    return HandoffOptions(capacity, gapWait, probeInterval, window)
}
