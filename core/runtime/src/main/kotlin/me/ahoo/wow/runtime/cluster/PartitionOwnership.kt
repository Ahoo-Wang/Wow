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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.ZeroAwaitingCounter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Who owns which partition of a lease group, as one node last read it: the live [owners] by partition, each a live
 * member with its address, and the live [members]. A lease whose holder is no longer a member is left out: its owner
 * is unknown, so a command for it runs locally.
 */
@InternalWowApi
data class RouteTable(val owners: Map<Int, Member> = emptyMap(), val members: List<Member> = emptyList()) {
    @InternalWowApi
    companion object {
        val EMPTY = RouteTable()
    }
}

/**
 * One renewal round of [node] in [group]: the [leases] it held, of which [lost] were found already expired or
 * taken, or went unrenewed for `leaseTtl − renewInterval`, and [failed] threw, and how long the round
 * took. A round longer than `leaseTtl − renewInterval` lets the node's own leases expire between renewals.
 */
@InternalWowApi
data class RenewalRound(
    val group: LeaseGroup,
    val node: String,
    val leases: Int,
    val lost: Int,
    val failed: Int,
    val duration: Duration,
)

/**
 * This node's share of the partitions of one lease [group] (–), without a leader: a
 * context's log, or another context's published topic that the service's subscriptions read, whose
 * [subscriptions] runtime then reads the topic instead of the log:
 *
 * - **Membership** is the node's in its service ([ServiceMembership]), shared by all its groups: one
 *   record listing the groups it takes part in, heartbeated every [ClusterOptions.heartbeatInterval], and one read of
 *   the members per interval, which this group's reconcile and routes reuse. The group's members are those listing
 *   it. A reconcile that runs longer than [ClusterOptions.reconcileTimeout], stuck stopping or starting a partition,
 *   takes the group off the node's record until the reconcile ends, so the others take the partitions it cannot run
 *   instead of handing them back to it. One still running after `wow.health.liveness.stall-timeout` fails the node's
 *   liveness, so its orchestrator restarts it: such a reconcile waits on a call that never returns, which only a
 *   restart ends.
 * - **Reconciling**: every [ClusterOptions.reconcileInterval], and at once when it notices the members changed, the
 *   node computes its target, the partitions HRW ([Hrw]) gives it among the live members, hands off the partitions it
 *   holds outside it and acquires those inside it. Members that briefly disagree are arbitrated by the leases and
 *   converge on the next round; a partition the old owner still holds is acquired once it is released or expires.
 * - **Leases** are renewed every [ClusterOptions.renewInterval], counted from the start of the previous round, all
 *   held leases of a round with one [LeaseStore.renew], so a round does not grow with the number
 *   held times the store's latency and asks the store's pool for one connection, not one per lease.
 *   Renewing starts before the first acquisition.
 *   Each round is reported to [renewalRounds] and logged, as a warning when it took more than half the margin
 *   `leaseTtl − renewInterval`. A lease lost to expiry stops the partition here, and so does one left unrenewed for
 *   `leaseTtl − renewInterval` (the store unreachable, say), without a release; the next reconcile acquires it anew.
 * - **Hand-off** of a partition goes in this order: stop accepting new commands for it, stop its command intake,
 *   which settles the commands it took and saves its checkpoint, wait for its other commands in
 *   flight for at most [ClusterOptions.drainTimeout], stop its subscriptions, which saves their checkpoints
 *   ([SubscriptionRuntime.stopPartition]), and release the lease, so the new owner acquires it on its next round.
 *   State caches are not handed over: the new owner loads from the snapshot and the log's tail, and each node drops
 *   the partition's cached states whenever it acquires, hands off or loses it ([partitionChanged]).
 * - **Routes**: every [ClusterOptions.routeRefreshInterval], and at once on [refreshSoon], the node reads the owners
 *   into its [routes], beside the members its membership read last. Each other member they show at an address not seen before is handed to [peers],
 *   apart from the refresh: the node connects to it there ([ClusterChannel.connect]) before forwarding to it.
 *
 * Acquiring a partition starts its subscriptions in [subscriptions] and its other log [groups]
 * ([SubscriptionRuntime.startPartition]), then the
 * same partition of the context's command topic in [intake], then its [timers], before the node accepts commands for
 * it. A hand-off stops the timers first, once the node stops accepting, so it sends no command it would not run.
 *
 * [stop] is a graceful leave: the node leaves the members at once, so the others take its partitions on their next
 * round, then hands off every partition it holds.
 */
@InternalWowApi
class PartitionOwnership internal constructor(
    val group: LeaseGroup,
    val node: String,
    /** This node's internal address, read when the node joins: a node may learn it once its port is bound. */
    private val addressOf: () -> String,
    /** The number of partitions of the context's log, or of the topic the group reads. */
    val partitions: Int,
    private val leases: LeaseStore,
    private val options: ClusterOptions = ClusterOptions(),
    subscriptions: SubscriptionRuntime? = null,
    private val context: CoroutineContext = Dispatchers.Default,
    /** Measures renewal rounds; a test passes its virtual time. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** Called after every renewal round, from the renewing coroutine: a metric hook. */
    private val renewalRounds: (RenewalRound) -> Unit = {},
    /** Called, in its own coroutine, for each other member the routes show at an address not seen before. */
    private val peers: suspend (Member) -> Unit = {},
    /**
     * The context's command intake: the runtime of its command topic, whose partitions are the log's,
     * started and stopped with each partition the node holds, so the owner of a log partition executes its commands.
     */
    private val intake: SubscriptionRuntime? = null,
    /**
     * The context's timer runners, one per log group: they send the timers of each
     * partition the node holds, from the partition's acquisition until its hand-off begins.
     */
    private val timers: List<TimerRunner> = emptyList(),
    /**
     * The subscription runtimes of the context's other log groups, each reading the partitions this
     * node holds in its group's log, started and stopped with [subscriptions].
     */
    groups: List<SubscriptionRuntime> = emptyList(),
    /**
     * Called with each partition this node acquires, before it starts it, and with each it hands off or loses, once
     * stopped: the context's state cache drops the partition's states.
     */
    private val partitionChanged: (Int) -> Unit = {},
    /**
     * The node's membership in its service on [leases], which every ownership of the node shares: one
     * record and one read of the members per node, whatever the number of groups. One of its own when not given.
     */
    membership: ServiceMembership? = null,
) {
    @Suppress("LongParameterList") // The parts of an ownership, as the primary constructor takes them.
    constructor(
        group: LeaseGroup,
        node: String,
        address: String,
        /** The number of partitions of the context's log, or of the topic the group reads. */
        partitions: Int,
        leases: LeaseStore,
        options: ClusterOptions = ClusterOptions(),
        subscriptions: SubscriptionRuntime? = null,
        context: CoroutineContext = Dispatchers.Default,
        /** Measures renewal rounds; a test passes its virtual time. */
        timeSource: TimeSource = TimeSource.Monotonic,
        /** Called after every renewal round, from the renewing coroutine: a metric hook. */
        renewalRounds: (RenewalRound) -> Unit = {},
        /** Called, in its own coroutine, for each other member the routes show at an address not seen before. */
        peers: suspend (Member) -> Unit = {},
    ) : this(
        group,
        node,
        fixedAddress(address),
        partitions,
        leases,
        options,
        subscriptions,
        context,
        timeSource,
        renewalRounds,
        peers,
    )

    private val membership: ServiceMembership =
        membership ?: ServiceMembership(group.service, node, addressOf, leases, options, context)

    init {
        require(this.membership.service == group.service && this.membership.node == node) {
            "The ownership of $group on $node needs the membership of ${group.service} on $node."
        }
    }

    /** The runtimes reading the partitions this node holds: the context's (or the topic's), then its other groups'. */
    private val runtimes: List<SubscriptionRuntime> = listOfNotNull(subscriptions) + groups

    /** This node's internal address, as the other members reach it. */
    val address: String get() = addressOf()
    private val lifecycle = Mutex()
    private val acquisitions = Semaphore(options.leaseParallelism)
    private val reconcileLock = Mutex()
    private val gates = ConcurrentHashMap<Int, Gate>()

    /** How long a lease may go unrenewed before this node takes it as lost: the margin before the next round. */
    private val unrenewedLimit: Duration = options.leaseTtl - options.renewInterval
    private val wakeReconcile = Channel<Unit>(Channel.CONFLATED)
    private val wakeRefresh = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var running: Running? = null

    @Volatile
    private var lastMembers: Set<String> = emptySet()

    /** When the reconcile running now started; null between reconciles. */
    @Volatile
    private var reconciling: TimeMark? = null

    /** Whether the node left the members because a reconcile is stuck. */
    @Volatile
    private var stuck = false

    /** The other members' addresses handed to [peers] so far, as `node@address`. */
    private val seenPeers = ConcurrentHashMap.newKeySet<String>()

    /** The greetings that fail, and since when. */
    private val greetings = PeerGreetings(timeSource, options.memberTtl)

    /** The members greeted and still members, as `node@address`. */
    internal val knownPeers: Set<String> get() = seenPeers

    init {
        require(node.isNotBlank()) { "A node has an id." }
        require(partitions >= 1) { "A log has at least one partition." }
    }

    /** The routes as last read from the lease store. */
    @Volatile
    var routes: RouteTable = RouteTable.EMPTY
        private set

    /** How long the reconcile running now has run; null between reconciles. */
    internal val reconcilingFor: Duration? get() = reconciling?.elapsedNow()

    /** The partitions HRW gave this node on the last reconcile. */
    @Volatile
    var target: Set<Int> = emptySet()
        private set

    /** The partitions this node holds and accepts commands for. */
    val owned: Set<Int> get() = gates.filterValues { it.open }.keys.toSortedSet()

    /** Whether the node takes part in the group. */
    val isRunning: Boolean get() = running != null

    /**
     * Joins the group: heartbeats, reconciles at once and keeps doing so until [stop]. A start that fails, the lease
     * store unreachable say, undoes what it did before it throws: it leaves the members, hands off
     * what it acquired and stops the runtimes it started, so the ownership can be started again.
     */
    suspend fun start(): Unit = lifecycle.withLock {
        check(running == null) { "$node is already part of $group." }
        requireAddress(address)
        val started = mutableListOf<SubscriptionRuntime>()
        var joined = false
        try {
            join(started)
            joined = true
        } finally {
            if (!joined) {
                withContext(NonCancellable) { abandon(started) }
            }
        }
    }

    private suspend fun join(started: MutableList<SubscriptionRuntime>) {
        runtimes.filterNot { it.isRunning }.forEach {
            started += it
            it.start()
        }
        if (intake != null && !intake.isRunning) {
            started += intake
            intake.start()
        }
        val root = SupervisorJob()
        val run = Running(root, CoroutineScope(context + root))
        running = run
        // Before the first acquisition: the leases acquired first must not wait for the last one to be renewed.
        run.upkeep.launch {
            while (true) {
                val started = timeSource.markNow()
                quietly("renew leases") { renew() }
                delay(options.renewInterval - started.elapsedNow())
            }
        }
        membership.join(group.name, ::membersRead)
        refreshRoutes()
        reconcile()
        run.membership.launch { every(options.heartbeatInterval, "check the reconcile") { checkStuck() } }
        run.membership.launch {
            while (true) {
                quietly("reconcile") { reconcile() }
                withTimeoutOrNull(options.reconcileInterval) { wakeReconcile.receive() }
            }
        }
        run.upkeep.launch {
            while (true) {
                withTimeoutOrNull(options.routeRefreshInterval) { wakeRefresh.receive() }
                quietly("refresh routes") { refreshRoutes() }
            }
        }
    }

    /**
     * Undoes a [start] that failed half-way, as [stop] would: leaves the members, hands off what the first reconcile
     * acquired, then stops the runtimes this start [started], not those already running before it.
     */
    private suspend fun abandon(started: List<SubscriptionRuntime>) {
        val run = running
        try {
            run?.membershipJob?.cancelAndJoin()
            quietly("leave $group") { membership.leave(group.name) }
            reconcileLock.withLock {
                target = emptySet()
                handOff(gates.keys.toList())
            }
            timers.forEach { quietly("stop the timers of $group") { it.stop() } }
            started.asReversed().forEach { quietly("stop the subscriptions of $group") { it.stop() } }
        } finally {
            running = null
            seenPeers.clear()
            greetings.retain(emptySet())
            lastMembers = emptySet()
            run?.root?.cancelAndJoin()
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
            quietly("leave $group") { membership.leave(group.name) }
            reconcileLock.withLock {
                target = emptySet()
                handOff(gates.keys.toList())
            }
            timers.forEach { it.stop() }
            intake?.stop()
            runtimes.forEach { it.stop() }
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
        membership.abort(group.name)
        gates.clear()
        timers.forEach { it.stop() }
        intake?.stop()
        runtimes.forEach { it.stop() }
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
        val gate = gates[partition]?.takeIf { it.open } ?: return null
        gate.inFlight.increment()
        try {
            // A hand-off may have stopped accepting meanwhile and seen nothing in flight: back out.
            return if (gate.open) command() else null
        } finally {
            gate.inFlight.decrement()
        }
    }

    /** Whether this node holds [partition] and accepts commands for it. */
    fun accepts(partition: Int): Boolean = gates[partition]?.open == true

    /** Whether this node is about to own [partition]: it is in its target, but not acquired yet. */
    fun pending(partition: Int): Boolean = partition in target && !gates.containsKey(partition)

    /**
     * Where a command for [partition] goes when this node does not accept it: the next owner while this node hands
     * it off (HRW among the other members), otherwise the owner of its live lease. Null when that is unknown or
     * stale, or would be this node: the command then runs locally.
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
    suspend fun reconcile(): Unit = reconcileLock.withLock {
        if (running == null) {
            return@withLock
        }
        reconciling = timeSource.markNow()
        try {
            dropLost()
            // The members the membership read last: no read of its own.
            val members = membership.membersOf(group.name).mapTo(HashSet()) { it.node }
            lastMembers = members
            val goal = Hrw.partitionsOf(node, members + node, partitions)
            target = goal
            handOff(gates.keys.filter { it !in goal })
            val missing = goal.filter { !gates.containsKey(it) }
            coroutineScope {
                missing.forEach { partition -> launch { acquisitions.withPermit { acquire(partition) } } }
            }
        } finally {
            reconciling = null
        }
    }

    /**
     * Reads the owners into [routes], beside the members the membership read last; a change of the members wakes the
     * reconciler.
     */
    suspend fun refreshRoutes() {
        val members = membership.membersOf(group.name)
        val byNode = members.associateBy { it.node }
        val leased = leases.owners(group.name)
        val owners = leased.mapNotNull { lease -> byNode[lease.owner]?.let { lease.partition to it } }.toMap()
        routes = RouteTable(owners, members)
        greetNew(members)
        val nodes = byNode.keys
        if (nodes != lastMembers) {
            lastMembers = nodes
            wakeReconcile.trySend(Unit)
        }
    }

    /**
     * Hands each other member of [members] at an address not seen before to [peers], in its own coroutine. A greeting
     * that fails is tried again on the next read of the routes: logged at INFO, without its trace, while
     * it fails for less than the member TTL, as a new member's address that does not resolve yet does, and at WARN
     * with its trace after.
     */
    private fun greetNew(members: List<Member>) {
        val run = running ?: return
        // The members that left are forgotten: one that comes back is greeted again.
        val current = members.mapTo(HashSet()) { "${it.node}@${it.address}" }
        seenPeers.retainAll(current)
        greetings.retain(current)
        members.filter { it.node != node && seenPeers.add("${it.node}@${it.address}") }.forEach { member ->
            run.upkeep.launch { greet(member) }
        }
    }

    private suspend fun greet(member: Member) {
        val peer = "${member.node}@${member.address}"
        try {
            peers(member)
            greetings.greeted(peer)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // Greeted again on the next read of the routes.
            seenPeers.remove(peer)
            if (greetings.failed(peer)) {
                logger.warn(failure) {
                    "$node failed to greet ${member.node} at ${member.address} in $group for " +
                        "${greetings.failingFor(peer)}; retrying on the next round."
                }
            } else {
                logger.info {
                    "$node could not greet ${member.node} at ${member.address} in $group yet ($failure); " +
                        "retrying on the next round."
                }
            }
        }
    }

    /** After each read of the service's members: a change of this group's members wakes the reconciler. */
    private fun membersRead() {
        val nodes = membership.membersOf(group.name).mapTo(HashSet()) { it.node }
        if (running != null && nodes != lastMembers) {
            lastMembers = nodes
            wakeReconcile.trySend(Unit)
        }
    }

    /**
     * Leaves the group's members while the reconcile running now outlasts [ClusterOptions.reconcileTimeout]: the node
     * then cannot take or stop partitions, so it stops claiming its share by HRW, and takes part again once the
     * reconcile ends. The membership heartbeats the change at once.
     */
    private suspend fun checkStuck() {
        val started = reconciling
        if (started != null && started.elapsedNow() > options.reconcileTimeout) {
            if (!stuck) {
                logger.error {
                    "$node has been reconciling $group for more than ${options.reconcileTimeout}; it leaves the " +
                        "members until the reconcile ends, so the others take the partitions it cannot run. A " +
                        "reconcile still running after wow.health.liveness.stall-timeout fails the node's " +
                        "liveness, so its orchestrator restarts it."
                }
                membership.stuck(group.name, true)
                stuck = true
            }
            return
        }
        if (stuck) {
            membership.stuck(group.name, false)
            stuck = false
            logger.warn { "$node reconciles $group again and is back among its members." }
        }
    }

    /** Not cancellable: a lease acquired is either tracked by a gate or released. */
    private suspend fun acquire(partition: Int) = withContext(NonCancellable) {
        val asked = timeSource.markNow()
        leases.acquire(group.name, partition, node, options.leaseTtl) ?: return@withContext
        // Another node may have committed to the partition's aggregates since this one last cached them.
        partitionChanged(partition)
        val gate = Gate(asked)
        try {
            runtimes.forEach { it.startPartition(partition) }
            intake?.startPartition(partition)
            timers.forEach { it.startPartition(partition) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            logger.warn(failure) { "$node failed to start partition $partition of $group; releasing it." }
            stopSubscriptions(partition)
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
                stopTimers(partition)
                // The intake stops taking the partition's commands, settles those in flight and saves its checkpoint.
                quietly("stop the command intake of partition $partition") { intake?.stopPartition(partition) }
                withTimeoutOrNull(options.drainTimeout) { gate.inFlight.awaitZero() } ?: logger.warn {
                    "$node handed off partition $partition of $group with commands still in flight."
                }
                stopSubscriptions(partition)
                quietly("release partition $partition") { leases.release(group.name, partition, node) }
                partitionChanged(partition)
                gates.remove(partition, gate)
                logger.info { "$node handed off partition $partition of $group." }
            }
        }.awaitAll()
    }

    /**
     * Renews every held lease once, all with one [LeaseStore.renew]. A partition the call does not
     * give back is lost, unless it was handed off meanwhile; a call that throws leaves every lease to the next round,
     * each lost only once it is overdue.
     */
    @Suppress("TooGenericExceptionCaught") // A failed renewal is retried on the next round.
    private suspend fun renew() {
        val started = timeSource.markNow()
        // A lease left unrenewed too long is taken as lost here, not renewed.
        val overdue = loseOverdue()
        val held = gates.entries.filter { !it.value.lost }.map { it.key to it.value }
        var lost = overdue
        var failed = 0
        if (held.isNotEmpty()) {
            val asked = timeSource.markNow()
            val renewed = try {
                leases.renew(
                    group.name,
                    held.map { it.first },
                    node,
                    options.leaseTtl
                ).mapTo(HashSet()) { it.partition }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn(
                    failure
                ) { "$node failed to renew ${held.size} leases of $group; retrying on the next round." }
                null
            }
            held.forEach { (partition, gate) ->
                when (renewalOf(partition, gate, asked, renewed)) {
                    Renewal.LOST -> lost++
                    Renewal.FAILED -> failed++
                    Renewal.RENEWED -> Unit
                }
            }
        }
        report(RenewalRound(group, node, held.size + overdue, lost, failed, started.elapsedNow()))
    }

    /** How [partition]'s lease fared in a round asked at [asked]: [renewed] the partitions renewed, null on a failure. */
    private fun renewalOf(partition: Int, gate: Gate, asked: TimeMark, renewed: Set<Int>?): Renewal {
        if (renewed == null) {
            return if (loseIfOverdue(partition, gate)) Renewal.LOST else Renewal.FAILED
        }
        if (gate.lost) {
            // Taken as lost while the round ran, overdue: the round reports it.
            return Renewal.LOST
        }
        if (partition in renewed) {
            gate.renewedAt = asked
            return Renewal.RENEWED
        }
        if (gates[partition] !== gate) {
            // Handed off and released meanwhile: nothing was lost.
            return Renewal.RENEWED
        }
        logger.warn { "$node lost the lease of partition $partition of $group." }
        gate.lost = true
        gate.accepting = false
        wakeReconcile.trySend(Unit)
        return Renewal.LOST
    }

    private fun report(round: RenewalRound) {
        val margin = options.leaseTtl - options.renewInterval
        if (round.duration > margin / 2) {
            logger.warn {
                "$node took ${round.duration} to renew ${round.leases} leases of $group, more than half the margin " +
                    "$margin before they expire; lost ${round.lost}, failed ${round.failed}."
            }
        } else {
            logger.debug { "$node renewed ${round.leases} leases of $group in ${round.duration}." }
        }
        try {
            renewalRounds(round)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            logger.warn(failure) { "The renewal round hook of $node in $group failed." }
        }
    }

    /**
     * Takes the leases left unrenewed for `leaseTtl − renewInterval` as lost here: the
     * store may be unreachable while the log is not, and the lease may expire before the next round renews it. The
     * partition stops as if its lease were lost, without a release the store would not take either; once renewals
     * work again, the next reconcile acquires it anew.
     */
    private fun loseOverdue(): Int = gates.count { (partition, gate) -> loseIfOverdue(partition, gate) }

    /** Takes [gate]'s lease as lost when it is overdue (see [loseOverdue]); whether it did now. */
    private fun loseIfOverdue(partition: Int, gate: Gate): Boolean {
        if (gate.lost || !gate.accepting || !gate.overdue) {
            return false
        }
        logger.error {
            "$node has not renewed the lease of partition $partition of $group for $unrenewedLimit; it stops the " +
                "partition before the lease may expire."
        }
        gate.lost = true
        gate.accepting = false
        wakeReconcile.trySend(Unit)
        return true
    }

    /** Stops the partitions whose lease expired before it was renewed; another node may own them now. */
    private suspend fun dropLost() {
        loseOverdue()
        gates.filterValues { it.lost }.forEach { (partition, gate) ->
            stopTimers(partition)
            quietly("stop the command intake of partition $partition") { intake?.stopPartition(partition) }
            stopSubscriptions(partition)
            partitionChanged(partition)
            gates.remove(partition, gate)
        }
    }

    private suspend fun stopTimers(partition: Int) = timers.forEach { runner ->
        quietly("stop the timers of partition $partition") { runner.stopPartition(partition) }
    }

    private suspend fun stopSubscriptions(partition: Int) = runtimes.forEach { runtime ->
        quietly("stop the subscriptions of partition $partition") { runtime.stopPartition(partition) }
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

    /**
     * A partition this node holds: whether it takes commands, and the commands in flight. [renewedAt] marks when the
     * lease was last acquired or renewed, taken before the call: once longer ago than `leaseTtl − renewInterval`, the
     * lease is [overdue] and the gate no longer [open], since the lease may expire before the next round renews it.
     */
    private inner class Gate(
        @Volatile var renewedAt: TimeMark,
    ) {
        @Volatile
        var accepting = true

        @Volatile
        var lost = false
        val inFlight = ZeroAwaitingCounter()

        val overdue: Boolean get() = renewedAt.elapsedNow() >= unrenewedLimit

        /** Whether the partition takes commands here: accepting, and its lease renewed recently enough. */
        val open: Boolean get() = accepting && !overdue
    }

    private enum class Renewal { RENEWED, LOST, FAILED }

    private class Running(val root: Job, scope: CoroutineScope) {
        val membershipJob = SupervisorJob(root)
        val membership = CoroutineScope(scope.coroutineContext + membershipJob)
        val upkeep = scope
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

private fun requireAddress(address: String): String {
    require(address.isNotBlank()) { "A node has an internal address." }
    return address
}

private fun fixedAddress(address: String): () -> String {
    requireAddress(address)
    return { address }
}
