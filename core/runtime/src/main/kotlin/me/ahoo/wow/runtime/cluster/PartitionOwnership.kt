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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Who owns which partition of a lease group, as one node last read it: the live [owners] by partition, each a live
 * member with its address, and the live [members]. A lease whose holder is no longer a member is left out: its owner
 * is unknown, so a command for it runs locally (decision D71).
 */
data class RouteTable(val owners: Map<Int, Member> = emptyMap(), val members: List<Member> = emptyList()) {
    companion object {
        val EMPTY = RouteTable()
    }
}

/**
 * This node's share of the partitions of one lease [group] (design 02 §3; decisions D70–D72), without a leader:
 *
 * - **Membership**: the node heartbeats its [address] every [ClusterOptions.heartbeatInterval].
 * - **Reconciling**: every [ClusterOptions.reconcileInterval], and at once when it notices the members changed, the
 *   node computes its target, the partitions HRW ([Hrw]) gives it among the live members, hands off the partitions it
 *   holds outside it and acquires those inside it. Members that briefly disagree are arbitrated by the leases and
 *   converge on the next round; a partition the old owner still holds is acquired once it is released or expires.
 * - **Leases** are renewed every [ClusterOptions.renewInterval]; a lease lost to expiry stops the partition here.
 * - **Hand-off** of a partition goes in this order: stop accepting new commands for it, wait for its commands in
 *   flight for at most [ClusterOptions.drainTimeout], stop its subscriptions, which saves their checkpoints
 *   ([SubscriptionRuntime.stopPartition]), and release the lease, so the new owner acquires it on its next round.
 *   State caches are not handed over: the new owner loads from the snapshot and the log's tail.
 * - **Routes**: every [ClusterOptions.routeRefreshInterval], and at once on [refreshSoon], the node reads the owners
 *   and members into its [routes].
 *
 * Acquiring a partition starts its subscriptions in [subscriptions] ([SubscriptionRuntime.startPartition]) before
 * the node accepts commands for it.
 *
 * [stop] is a graceful leave: the node leaves the members at once, so the others take its partitions on their next
 * round, then hands off every partition it holds.
 */
class PartitionOwnership(
    val group: LeaseGroup,
    val node: String,
    val address: String,
    /** The number of partitions of the context's log. */
    val partitions: Int,
    private val leases: LeaseStore,
    private val options: ClusterOptions = ClusterOptions(),
    private val subscriptions: SubscriptionRuntime? = null,
    private val context: CoroutineContext = Dispatchers.Default,
) {
    private val lifecycle = Mutex()
    private val reconciling = Mutex()
    private val gates = ConcurrentHashMap<Int, Gate>()
    private val wakeReconcile = Channel<Unit>(Channel.CONFLATED)
    private val wakeRefresh = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var running: Running? = null

    @Volatile
    private var lastMembers: Set<String> = emptySet()

    init {
        require(node.isNotBlank()) { "A node has an id." }
        require(address.isNotBlank()) { "A node has an internal address." }
        require(partitions >= 1) { "A log has at least one partition." }
    }

    /** The routes as last read from the lease store. */
    @Volatile
    var routes: RouteTable = RouteTable.EMPTY
        private set

    /** The partitions HRW gave this node on the last reconcile. */
    @Volatile
    var target: Set<Int> = emptySet()
        private set

    /** The partitions this node holds and accepts commands for. */
    val owned: Set<Int> get() = gates.filterValues { it.accepting }.keys.toSortedSet()

    /** Whether the node takes part in the group. */
    val isRunning: Boolean get() = running != null

    /** Joins the group: heartbeats, reconciles at once and keeps doing so until [stop]. */
    suspend fun start(): Unit = lifecycle.withLock {
        check(running == null) { "$node is already part of $group." }
        if (subscriptions != null && !subscriptions.isRunning) {
            subscriptions.start()
        }
        leases.heartbeat(group.name, node, address, options.memberTtl)
        val root = SupervisorJob()
        val run = Running(root, CoroutineScope(context + root))
        running = run
        refreshRoutes()
        reconcile()
        run.membership.launch { every(options.heartbeatInterval, "heartbeat") { heartbeat() } }
        run.membership.launch {
            while (true) {
                quietly("reconcile") { reconcile() }
                withTimeoutOrNull(options.reconcileInterval) { wakeReconcile.receive() }
            }
        }
        run.upkeep.launch { every(options.renewInterval, "renew leases") { renew() } }
        run.upkeep.launch {
            while (true) {
                withTimeoutOrNull(options.routeRefreshInterval) { wakeRefresh.receive() }
                quietly("refresh routes") { refreshRoutes() }
            }
        }
    }

    /**
     * Leaves the group gracefully: leaves the members, hands off every partition this node holds (see the class
     * description for the order), and stops the subscriptions.
     */
    suspend fun stop(): Unit = lifecycle.withLock {
        val run = running ?: return@withLock
        try {
            run.membershipJob.cancelAndJoin()
            quietly("leave $group") { leases.leave(group.name, node) }
            reconciling.withLock {
                target = emptySet()
                handOff(gates.keys.toList())
            }
            subscriptions?.stop()
        } finally {
            running = null
            run.root.cancelAndJoin()
        }
    }

    /**
     * Stops at once without handing off or leaving, as a crash would: the node's membership and leases expire on
     * their own. For tests of failures.
     */
    internal suspend fun abort(): Unit = lifecycle.withLock {
        val run = running ?: return@withLock
        running = null
        run.root.cancelAndJoin()
        gates.clear()
        subscriptions?.stop()
    }

    /** Asks for the routes to be read again soon, such as after a forward failed. */
    fun refreshSoon() {
        wakeRefresh.trySend(Unit)
    }

    /**
     * Runs [command] for [partition] when this node holds the partition and accepts commands for it, and returns
     * what it returned; null, without running it, otherwise. A hand-off of the partition waits for the commands
     * running here.
     */
    suspend fun <T : Any> runIfAccepting(partition: Int, command: suspend () -> T): T? {
        val gate = gates[partition]?.takeIf { it.accepting } ?: return null
        gate.inFlight.update { it + 1 }
        try {
            // A hand-off may have stopped accepting meanwhile and seen nothing in flight: back out.
            return if (gate.accepting) command() else null
        } finally {
            gate.inFlight.update { it - 1 }
        }
    }

    /** Whether this node holds [partition] and accepts commands for it. */
    fun accepts(partition: Int): Boolean = gates[partition]?.accepting == true

    /** Whether this node is about to own [partition]: it is in its target, but not acquired yet. */
    fun pending(partition: Int): Boolean = partition in target && !gates.containsKey(partition)

    /**
     * Where a command for [partition] goes when this node does not accept it: the next owner while this node hands
     * it off (HRW among the other members), otherwise the owner of its live lease. Null when that is unknown or
     * stale, or would be this node: the command then runs locally (decision D71).
     */
    fun forwardTarget(partition: Int): Member? {
        val routes = routes
        val owner = if (gates.containsKey(partition)) {
            val others = routes.members.filter { it.node != node }
            Hrw.owner(partition, others.map { it.node })?.let { next -> others.first { it.node == next } }
        } else {
            routes.owners[partition]
        }
        return owner?.takeIf { it.node != node }
    }

    /** Compares the partitions held with the HRW target once, handing off and acquiring what differs. */
    suspend fun reconcile(): Unit = reconciling.withLock {
        if (running == null) {
            return@withLock
        }
        dropLost()
        val members = leases.members(group.name).map { it.node }.toSet()
        lastMembers = members
        val goal = Hrw.partitionsOf(node, members + node, partitions)
        target = goal
        handOff(gates.keys.filter { it !in goal })
        for (partition in goal) {
            if (!gates.containsKey(partition)) {
                acquire(partition)
            }
        }
    }

    /** Reads the owners and members into [routes]; a change of the members wakes the reconciler. */
    suspend fun refreshRoutes() {
        val members = leases.members(group.name)
        val byNode = members.associateBy { it.node }
        val leased = leases.owners(group.name)
        val owners = leased.mapNotNull { lease -> byNode[lease.owner]?.let { lease.partition to it } }.toMap()
        routes = RouteTable(owners, members)
        val nodes = byNode.keys
        if (nodes != lastMembers) {
            lastMembers = nodes
            wakeReconcile.trySend(Unit)
        }
    }

    private suspend fun heartbeat() {
        leases.heartbeat(group.name, node, address, options.memberTtl)
    }

    /** Not cancellable: a lease acquired is either tracked by a gate or released. */
    private suspend fun acquire(partition: Int) = withContext(NonCancellable) {
        leases.acquire(group.name, partition, node, options.leaseTtl) ?: return@withContext
        val gate = Gate()
        try {
            subscriptions?.startPartition(partition)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            logger.warn(failure) { "$node failed to start partition $partition of $group; releasing it." }
            quietly("release partition $partition") { leases.release(group.name, partition, node) }
            return@withContext
        }
        gates[partition] = gate
        logger.info { "$node acquired partition $partition of $group." }
    }

    /** Hands off [handed] concurrently, each in order: stop accepting, drain, save checkpoints, release. */
    private suspend fun handOff(handed: List<Int>) = coroutineScope {
        handed.map { partition ->
            async {
                val gate = gates[partition] ?: return@async
                gate.accepting = false
                withTimeoutOrNull(options.drainTimeout) { gate.inFlight.first { it <= 0 } } ?: logger.warn {
                    "$node handed off partition $partition of $group with commands still in flight."
                }
                quietly("stop the subscriptions of partition $partition") {
                    subscriptions?.stopPartition(partition)
                }
                quietly("release partition $partition") { leases.release(group.name, partition, node) }
                gates.remove(partition, gate)
                logger.info { "$node handed off partition $partition of $group." }
            }
        }.awaitAll()
    }

    private suspend fun renew() {
        for ((partition, gate) in gates) {
            if (!gate.lost && leases.renew(group.name, partition, node, options.leaseTtl) == null) {
                logger.warn { "$node lost the lease of partition $partition of $group." }
                gate.lost = true
                gate.accepting = false
                wakeReconcile.trySend(Unit)
            }
        }
    }

    /** Stops the partitions whose lease expired before it was renewed; another node may own them now. */
    private suspend fun dropLost() {
        gates.filterValues { it.lost }.forEach { (partition, gate) ->
            quietly("stop the subscriptions of partition $partition") { subscriptions?.stopPartition(partition) }
            gates.remove(partition, gate)
        }
    }

    private suspend fun every(interval: Duration, action: String, block: suspend () -> Unit) {
        while (true) {
            delay(interval)
            quietly(action, block)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed round is retried on the next one.
    private suspend fun quietly(action: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "$node failed to $action in $group; retrying on the next round." }
        }
    }

    /** A partition this node holds: whether it takes commands, and the commands in flight. */
    private class Gate {
        @Volatile
        var accepting = true

        @Volatile
        var lost = false
        val inFlight = MutableStateFlow(0)
    }

    private class Running(val root: Job, scope: CoroutineScope) {
        val membershipJob = SupervisorJob(root)
        val membership = CoroutineScope(scope.coroutineContext + membershipJob)
        val upkeep = scope
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
