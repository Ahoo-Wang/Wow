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
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
 *   Renewing starts before the first acquisition, and covers a partition from the moment its lease is taken, while
 *   its runtimes, intake and timers start: a start slower than the margin keeps its lease.
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
 * it: while they start, the partition is still [pending], its lease renewed with the others. A hand-off stops the timers first, once the node stops accepting, so it sends no command it would not run.
 * The partitions the first reconcile of a [start] acquires read nothing, their entries or their parked retries, until
 * it has started all of them, or until the job [start] is given completes: a backlog the first of them would read at
 * once (a history migrate-data imported, a long stop) would slow down the starts of the others. Without a job, the
 * hold lasts at most [ClusterOptions.reconcileTimeout], so a reconcile stuck starting one partition does not hold the
 * others back. The partitions later reconciles acquire read at once.
 *
 * [stop] is a graceful leave: the node leaves the members at once, so the others take its partitions on their next
 * round, then hands off every partition it holds.
 *
 * [stepOut] is the leave of a node that met a fatal error ([me.ahoo.wow.runtime.health.NodeFatals]): for good, within a
 * bound, and whatever the hand-off comes to, renewing no lease from then on, so a partition it could not hand off is
 * the others' once its lease expires. It keeps reading the routes, so a command the node is still given goes to the
 * partition's new owner.
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

    /** The gates of the partitions held: only the ownership puts, removes and clears them; [renewals] reads them. */
    private val gates = ConcurrentHashMap<Int, PartitionGate>()

    /** How long a lease may go unrenewed before this node takes it as lost: the margin before the next round. */
    private val unrenewedLimit: Duration = options.leaseTtl - options.renewInterval
    private val wakeReconcile = Channel<Unit>(Channel.CONFLATED)
    private val wakeRefresh = Channel<Unit>(Channel.CONFLATED)

    /** The renewals and the fencing of the leases held: they read [gates] and flip a gate's own fields only. */
    private val renewals =
        LeaseRenewals(group, node, leases, options, timeSource, gates, unrenewedLimit, renewalRounds) {
            wakeReconcile.trySend(Unit)
        }

    @Volatile
    private var running: Running? = null

    /** When the reconcile running now started; null between reconciles. */
    @Volatile
    private var reconciling: TimeMark? = null

    /** Whether the node left the members because a reconcile is stuck. */
    @Volatile
    private var stuck = false

    /**
     * Whether the node stepped out of the group for good ([stepOut]): it neither joins, reconciles nor renews any
     * more. Set before anything else of the step-out, so a start racing it backs out.
     */
    private val steppedOut = AtomicBoolean()

    init {
        require(node.isNotBlank()) { "A node has an id." }
        require(partitions >= 1) { "A log has at least one partition." }
    }

    /** The other members as this node last read them, and the greetings of those at a new address. */
    private val peerRoutes =
        PeerRoutes(group, node, this.membership, leases, peers, PeerGreetings(timeSource, options.memberTtl)) {
            wakeReconcile.trySend(Unit)
        }

    /** The members greeted and still members, as `node@address`. */
    internal val knownPeers: Set<String> get() = peerRoutes.knownPeers

    /** The routes as last read from the lease store. */
    val routes: RouteTable get() = peerRoutes.routes

    /**
     * Reads the owners into [routes], beside the members the membership read last; a change of the members wakes the
     * reconciler. Once the node stepped out ([stepOut]), the membership reads no members for it, so it reads them here.
     */
    suspend fun refreshRoutes() = peerRoutes.refresh(steppedOut.get(), running?.upkeep)

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
     *
     * The partitions the first reconcile starts read nothing until [reads] completes, or, without it, until that
     * reconcile has started all of them or has run for [ClusterOptions.reconcileTimeout]: a node passes one job to
     * every context it starts, and bounds it itself.
     */
    suspend fun start(reads: Job? = null): Unit = lifecycle.withLock {
        check(running == null) { "$node is already part of $group." }
        check(!steppedOut.get()) { "$node stepped out of $group after a fatal error." }
        requireAddress(address)
        val started = mutableListOf<SubscriptionRuntime>()
        var joined = false
        try {
            join(started, reads)
            joined = true
        } finally {
            if (!joined) {
                withContext(NonCancellable) { abandon(started) }
            }
        }
    }

    private suspend fun join(started: MutableList<SubscriptionRuntime>, reads: Job?) {
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
        run.renewals = run.upkeep.launch {
            while (!steppedOut.get()) {
                val started = timeSource.markNow()
                quietly("renew leases") { renewals.renew() }
                delay(options.renewInterval - started.elapsedNow())
            }
        }
        membership.join(group.name) { if (running != null) peerRoutes.membersRead() }
        // A step-out that ran meanwhile saw no running ownership to leave: this start leaves, and fails.
        check(!steppedOut.get()) { "$node stepped out of $group after a fatal error while joining it." }
        refreshRoutes()
        val own = if (reads == null) Job() else null
        val release = own?.let { held ->
            run.upkeep.launch {
                delay(options.reconcileTimeout)
                logger.warn {
                    "$node has run its first reconcile of $group for more than ${options.reconcileTimeout}: the " +
                        "partitions it started read from now on, without waiting for the others."
                }
                held.complete()
            }
        }
        try {
            reconcile(reads ?: own)
        } finally {
            release?.cancel()
            own?.complete()
        }
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
            peerRoutes.forget()
            run?.root?.cancelAndJoin()
        }
    }

    /**
     * Leaves the group gracefully: leaves the members, hands off every partition this node holds (see the class
     * description for the order), and stops the timers, the intake and the subscriptions whatever the hand-off came to.
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
        } finally {
            withContext(NonCancellable) { stopReads() }
            running = null
            run.root.cancelAndJoin()
        }
    }

    /**
     * Leaves the group for good, the node having met a fatal error: leaves the members at once, so the others take its
     * partitions on their next round, and hands off every partition it holds as [stop] does, within [bound]. Whatever
     * the hand-off comes to, done, past [bound], or failed (out of memory again, say), the node renews no lease from then
     * on: a partition it still holds is the others' once its lease expires, and its subscriptions, intake and timers
     * are stopped, within [bound] again. It reconciles no more, but keeps reading the routes, so a command it is still
     * given is forwarded to the partition's new owner; [stop] ends that. Stepping out twice does nothing.
     */
    internal suspend fun stepOut(bound: Duration) {
        if (!steppedOut.compareAndSet(false, true)) {
            return
        }
        // A start or a stop stuck on a store holds the lifecycle: past the bound, the step-out goes on without it.
        val locked = withTimeoutOrNull(bound) { lifecycle.lock() } != null
        try {
            running?.let { stepOut(it, bound) }
        } finally {
            if (locked) {
                lifecycle.unlock()
            }
        }
    }

    private suspend fun stepOut(run: Running, bound: Duration) {
        var handed = false
        try {
            handed = withTimeoutOrNull(bound) {
                // Out of the members first: the reconcile may wait on a store, an acquisition not cancellable, and
                // until it ends the group would stay on the node's record, its share by HRW the others' to leave.
                run.membershipJob.cancel()
                quietly("leave $group") { membership.leave(group.name) }
                run.membershipJob.join()
                reconcileLock.withLock {
                    target = emptySet()
                    handOff(gates.keys.toList())
                }
                true
            } == true
        } finally {
            // Not one lease more is renewed, and nothing the node still holds takes a command.
            run.renewals?.cancel()
            gates.values.forEach { it.accepting = false }
            if (!handed) {
                logger.error {
                    "$node did not hand off its partitions of $group within $bound after its fatal error; it renews " +
                        "none of their leases, which expire within ${options.leaseTtl} for the others to take."
                }
                withContext(NonCancellable) { withTimeoutOrNull(bound) { stopReads() } }
            }
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
        stopReads()
    }

    /** Stops the timers, the intake and the subscriptions, each failure logged: a stop leaves none of them running. */
    private suspend fun stopReads() {
        timers.forEach { quietly("stop the timers of $group") { it.stop() } }
        quietly("stop the command intake of $group") { intake?.stop() }
        runtimes.forEach { quietly("stop the subscriptions of $group") { it.stop() } }
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

    /** Whether this node is about to own [partition]: it is in its target, but not acquired, or not started, yet. */
    fun pending(partition: Int): Boolean = partition in target && gates[partition]?.starting != false

    /**
     * Where a command for [partition] goes when this node does not accept it: the next owner while this node hands
     * it off (HRW among the other members), otherwise the owner of its live lease. Null when that is unknown or
     * stale, or would be this node: the command then runs locally.
     */
    fun forwardTarget(partition: Int): Member? {
        val routes = routes
        val owner = if (gates[partition]?.starting == false) {
            val others = routes.members.filter { it.node != node }
            Hrw.owner(partition, others.map { it.node })?.let { next -> others.first { it.node == next } }
        } else {
            routes.owners[partition]
        }
        return owner?.takeIf { it.node != node }
    }

    /** Compares the partitions held with the HRW target once, handing off and acquiring what differs. */
    suspend fun reconcile(): Unit = reconcile(null)

    /** [reconcile], the partitions it acquires reading once [reads] completes when given. */
    private suspend fun reconcile(reads: Job?): Unit = reconcileLock.withLock {
        if (running == null || steppedOut.get()) {
            return@withLock
        }
        reconciling = timeSource.markNow()
        try {
            dropLost()
            // The members the membership read last: no read of its own.
            val members = membership.membersOf(group.name).mapTo(HashSet()) { it.node }
            peerRoutes.membersSeen(members)
            val goal = Hrw.partitionsOf(node, members + node, partitions)
            target = goal
            handOff(gates.keys.filter { it !in goal })
            val missing = goal.filter { !gates.containsKey(it) }
            coroutineScope {
                missing.forEach { partition -> launch { acquisitions.withPermit { acquire(partition, reads) } } }
            }
        } finally {
            reconciling = null
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

    /**
     * Not cancellable: a lease acquired is either tracked by a gate or released. The partition's subscriptions read once
     * [reads] completes, when given.
     */
    private suspend fun acquire(partition: Int, reads: Job?) = withContext(NonCancellable) {
        val asked = timeSource.markNow()
        leases.acquire(group.name, partition, node, options.leaseTtl) ?: return@withContext
        // Tracked from now on, so the renewals keep the lease while the partition starts; it takes no command yet.
        val gate = PartitionGate(asked, unrenewedLimit)
        gates[partition] = gate
        try {
            // Another node may have committed to the partition's aggregates since this one last cached them.
            partitionChanged(partition)
            runtimes.forEach { it.startPartition(partition, reads) }
            intake?.startPartition(partition, reads)
            timers.forEach { it.startPartition(partition) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            logger.warn(failure) { "$node failed to start partition $partition of $group; releasing it." }
            stopSubscriptions(partition)
            quietly("release partition $partition") { leases.release(group.name, partition, node) }
            gates.remove(partition, gate)
            return@withContext
        }
        gate.started()
        if (gate.lost) {
            // Lost while it started: the next reconcile stops it, as any partition whose lease was lost.
            logger.warn { "$node lost the lease of partition $partition of $group while starting it." }
        } else {
            logger.info { "$node acquired partition $partition of $group." }
        }
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
                quietly("drop the cached states of partition $partition") { partitionChanged(partition) }
                gates.remove(partition, gate)
                logger.info { "$node handed off partition $partition of $group." }
            }
        }.awaitAll()
    }

    /** Stops the partitions whose lease expired before it was renewed; another node may own them now. */
    private suspend fun dropLost() {
        renewals.loseOverdue()
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

    private suspend fun stopSubscriptions(partition: Int) = coroutineScope {
        val action = "stop the subscriptions of partition $partition"
        runtimes.forEach { launch { quietly(action) { it.stopPartition(partition) } } }
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

    private class Running(val root: Job, scope: CoroutineScope) {
        val membershipJob = SupervisorJob(root)
        val membership = CoroutineScope(scope.coroutineContext + membershipJob)
        val upkeep = scope

        /** The renewals' loop, which [stepOut] ends. */
        @Volatile
        var renewals: Job? = null
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
