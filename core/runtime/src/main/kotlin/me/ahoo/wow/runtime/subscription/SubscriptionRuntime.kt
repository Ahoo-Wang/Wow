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

package me.ahoo.wow.runtime.subscription

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Runs the subscriptions of one subscription [group] over the partitions this node owns (–§3).
 *
 * Which partitions those are is decided outside, by the partition leases
 * ([me.ahoo.wow.runtime.cluster.PartitionOwnership]): passed to [start], then moved one at a time with
 * [startPartition] and [stopPartition] as the node acquires and hands off partitions. For each partition
 * the runtime reads the log once and fans the entries out to every subscription ([PartitionRunner]); each
 * subscription keeps its own checkpoint per partition, runs one aggregate's entries in version order and different
 * aggregates in parallel, and parks an aggregate whose handler keeps failing ([SubscriptionWorker]).
 *
 * Duplicates are possible above a checkpoint (an entry handled but not yet saved when the node stopped or crashed),
 * never below it, so handlers are idempotent by (`ref`, `version`).
 *
 * It takes part in waits: a subscription signals, through [signals], the wait an entry carries when
 * the wait's next hop is that subscription; and as the [SubscriptionDirectory] of its group it tells a wait which
 * subscriptions there are, of what kind, and whether one has handled a stream.
 *
 * The same runtime reads another context's published topic instead of the log
 * ([me.ahoo.wow.runtime.source.TopicSubscriptions]): its partitions are leased the same way, its entries fanned out
 * through one shared tail per partition, its checkpoints (the offset after the last record completed) kept in the
 * same store, and its parked aggregates in the same ledger, each with the offset to re-scan from.
 */
@InternalWowApi
class SubscriptionRuntime internal constructor(
    val group: String,
    private val source: EntrySource,
    private val checkpoints: CheckpointStore,
    internal val ledger: FailureLedger,
    subscriptions: Collection<Subscription> = emptyList(),
    internal val options: SubscriptionOptions = SubscriptionOptions(),
    private val clock: Clock = Clock.systemUTC(),
    private val context: CoroutineContext = Dispatchers.Default,
    private val signals: SignalSender = SignalSender.NONE,
    private val notifier: ParkedNotifier = ParkedNotifier.NONE,
    /** Sees each entry its subscriptions handle. */
    private val observer: Observer = Observer.NONE,
    /** How its subscriptions classify their handlers' failures. */
    private val classification: FailureClassification = RuntimeFailures.DEFAULT,
    /**
     * Whether its first start warns of the group's [orphans]: false for a runtime whose group's checkpoints another
     * runtime of the same name holds too, which tells them (a context's command intake shares its default log
     * group's), since the other's subscriptions are none of its own.
     */
    private val tellsOrphans: Boolean = true,
) : SubscriptionDirectory, SubscriptionPauses {
    @Suppress("LongParameterList") // The parts of a runtime, as the primary constructor takes them.
    constructor(
        group: String,
        log: EventLog,
        checkpoints: CheckpointStore,
        ledger: FailureLedger,
        subscriptions: Collection<Subscription> = emptyList(),
        options: SubscriptionOptions = SubscriptionOptions(),
        clock: Clock = Clock.systemUTC(),
        context: CoroutineContext = Dispatchers.Default,
        signals: SignalSender = SignalSender.NONE,
        notifier: ParkedNotifier = ParkedNotifier.NONE,
        observer: Observer = Observer.NONE,
        classification: FailureClassification = RuntimeFailures.DEFAULT,
    ) : this(
        group,
        LogSource(log),
        checkpoints,
        ledger,
        subscriptions,
        options,
        clock,
        context,
        signals,
        notifier,
        observer,
        classification,
    )

    private val lifecycle = Mutex()
    private val retries = ParkedRetries(group, ledger, options.parked, clock, notifier) { directory[it]?.context }
    private val subscriptions = LinkedHashMap<String, Subscription>()

    /** The registered subscriptions by name, read by waits from any thread. */
    private val directory = ConcurrentHashMap<String, Subscription>()

    @Volatile
    private var running: Running? = null

    /** Whether the first start told of the checkpoints no subscription of the group has. */
    private var orphansTold = false

    init {
        require(group.isNotBlank()) { "A subscription group needs a name." }
        subscriptions.forEach(::register)
    }

    private fun register(subscription: Subscription) {
        require(subscriptions.putIfAbsent(subscription.name, subscription) == null) {
            "Subscription ${subscription.name} is already registered in group $group."
        }
        // A retry budget that does not add up fails the assembly, not the first partition the node acquires.
        options.retryOf(subscription)
        directory[subscription.name] = subscription
    }

    /** Whether the runtime is running. */
    val isRunning: Boolean get() = running != null

    /** The partitions the runtime is reading now. */
    val partitions: Set<Int> get() = running?.partitions?.keys?.toSet().orEmpty()

    /** Starts reading [partitions], each subscription from its checkpoint; more can be started later. */
    suspend fun start(partitions: Set<Int> = emptySet()): Unit = lifecycle.withLock {
        check(running == null) { "Subscription group $group is already running." }
        partitions.forEach(::requirePartition)
        val root = SupervisorJob()
        val run = Running(root, CoroutineScope(context + root))
        var started = false
        try {
            val parked = subscriptions.values.associate { it.name to parkedIn(it, partitions) }
            // Who read the stream before this start: a subscription that starts where it joins reads it from the
            // beginning only when none did, whichever partition or subscription starts first.
            val saved = checkpoints.list(group)
            run.joined = saved.mapTo(HashSet()) { it.subscription }
            if (tellsOrphans && !orphansTold) {
                orphansTold = true
                tellOrphans(saved)
            }
            partitions.forEach { open(run, it, parked) }
            started = true
        } finally {
            if (!started) {
                root.cancel()
            }
        }
        run.scope.launch {
            while (true) {
                delay(options.checkpointInterval)
                flushQuietly(run.workers.values)
            }
        }
        run.scope.launch {
            while (true) {
                delay(options.parked.scanInterval)
                scanQuietly(run)
            }
        }
        running = run
    }

    /** Runs the parked entries due in the partitions [run] reads ([ParkedRetries]); a failed scan waits for the next. */
    @Suppress("TooGenericExceptionCaught") // A failed read of the ledger is retried on the next scan.
    private suspend fun scanQuietly(run: Running) {
        try {
            // A paused partition's entries wait: their resumes would fail the same way, and the ledger may be down.
            retries.scan(run.scope, directory.keys.sorted(), run.partitions.keys.toSet()) { subscription, ref ->
                run.workers[subscription to source.partitionOf(ref)]?.takeIf { it.paused == null }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to scan the parked entries of group $group; retrying on the next scan." }
        }
    }

    /**
     * Starts reading [partition] on the running runtime, each subscription from its checkpoint: the node has just
     * acquired it. Nothing happens when it is read already.
     */
    suspend fun startPartition(partition: Int): Unit = lifecycle.withLock {
        requirePartition(partition)
        val run = checkNotNull(running) { "Subscription group $group is not running." }
        if (!run.partitions.containsKey(partition)) {
            open(run, partition, subscriptions.values.associate { it.name to parkedIn(it, listOf(partition)) })
        }
    }

    /**
     * Stops reading [partition] as [stop] does for all of them: waits at most [SubscriptionOptions.stopTimeout] for
     * its entries in flight and saves its checkpoints, so the node can hand the partition off. The
     * other partitions keep running. Nothing happens when it is not read.
     */
    suspend fun stopPartition(partition: Int): Unit = lifecycle.withLock {
        val run = running ?: return@withLock
        val part = run.partitions.remove(partition) ?: return@withLock
        try {
            halt(listOf(part))
        } finally {
            part.workers.forEach { run.workers.remove(it.subscription.name to partition) }
        }
    }

    private fun requirePartition(partition: Int) = source.requirePartition(partition)

    private suspend fun open(run: Running, partition: Int, parked: Map<String, Map<Int, Map<AggregateRef, Long>>>) {
        val part = PartitionRun(partition, run)
        run.partitions[partition] = part
        val workers = subscriptions.values.map { worker(run, part, it, parked.getValue(it.name), run.joined) }
        part.runner.start(workers)
    }

    /**
     * Registers [subscription]. On a running runtime it starts at once in every partition: it catches up from its
     * checkpoint (or as [Subscription.startFrom] says) on its own read, then joins the shared tail.
     */
    suspend fun add(subscription: Subscription): Unit = lifecycle.withLock {
        register(subscription)
        val run = running ?: return@withLock
        val parked = parkedIn(subscription, run.partitions.keys)
        val joined = joinedSubscriptions()
        for (part in run.partitions.values) {
            part.runner.attach(worker(run, part, subscription, parked, joined))
        }
    }

    /**
     * Stops reading, waits at most [SubscriptionOptions.stopTimeout] for the entries in flight, abandons the rest,
     * and saves every checkpoint. An abandoned entry is above the saved checkpoint and is handled after a restart.
     */
    suspend fun stop(): Unit = lifecycle.withLock {
        val run = running ?: return@withLock
        running = null
        try {
            halt(run.partitions.values.toList())
        } finally {
            run.root.cancelAndJoin()
        }
    }

    /** Stops reading [parts], drains them within the stop timeout, abandons the rest and saves their checkpoints. */
    private suspend fun halt(parts: List<PartitionRun>) {
        parts.forEach { it.readersJob.cancelAndJoin() }
        val workers = parts.flatMap { it.workers }
        withTimeoutOrNull(options.stopTimeout) { workers.forEach { it.awaitDrained() } }
        parts.forEach { it.lanesJob.cancelAndJoin() }
        workers.forEach { it.abandon() }
        workers.forEach { it.flush() }
    }

    /**
     * Handles [subscription]'s parked aggregate [ref] again from its parked version, then resolves its ledger
     * entry; the aggregate's new entries are handled after it, in version order (see [SubscriptionWorker.resume]).
     * The runtime must be running the aggregate's partition.
     *
     * @return true when the aggregate is no longer parked; false when it was not parked or failed again.
     */
    suspend fun resume(subscription: String, ref: AggregateRef): Boolean {
        val worker = checkNotNull(workerOf(subscription, ref)) {
            "Subscription $subscription of group $group is not running the partition of $ref here."
        }
        return worker.resume(ref)
    }

    /**
     * Resolves [subscription]'s parked aggregate [ref] without handling what was skipped; its new entries are
     * handled from now on.
     *
     * @return true when the aggregate was parked; false when it was not, or a resume of it is running.
     */
    suspend fun skip(subscription: String, ref: AggregateRef): Boolean {
        require(subscription in subscriptions) { "No subscription $subscription in group $group." }
        val worker = workerOf(subscription, ref) ?: return ledger.resolve(subscription, ref) != null
        return worker.skip(ref)
    }

    /** The names of the subscriptions registered, read from any thread. */
    val subscriptionNames: Set<String> get() = directory.keys.toSet()

    /** The bounded context [subscription] belongs to ([Subscription.context]), null when it is not registered. */
    fun contextOf(subscription: String): String? = directory[subscription]?.context

    /**
     * Whether the runtime runs [subscription] in [ref]'s partition here now: [resume] and [skip] act on the aggregate
     * at once. Otherwise its owner is another node, which takes an operator's request from the ledger.
     */
    suspend fun runsPartitionOf(subscription: String, ref: AggregateRef): Boolean = workerOf(subscription, ref) != null

    override fun kindOf(context: String, subscription: String): FunctionKind? =
        directory[subscription]?.takeIf { it.filter.context == context }?.kind

    /** Whether [subscription]'s filter takes streams of [type]: its context, and its aggregates when it names any. */
    override fun covers(subscription: String, type: AggregateType): Boolean {
        val filter = directory[subscription]?.filter ?: return false
        return filter.context == type.context && (filter.aggregates.isEmpty() || type.name in filter.aggregates)
    }

    /**
     * Whether [subscription] is past [stream] for good: its progress in the stream's partition (the
     * running worker's watermark, else the saved checkpoint) is at or past the stream's position, and the stream's
     * aggregate is not parked.
     */
    override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean {
        if (kindOf(stream.ref.type.context, subscription) == null) {
            return false
        }
        val position = source.positionOf(stream.ref, stream.version) ?: return false
        val progress = running?.workers?.get(subscription to position.partition)?.watermark
            ?: checkpoints.load(group, subscription, position.partition)
            ?: 0L
        return progress >= position.sequence && !ledger.isParked(subscription, stream.ref)
    }

    /**
     * How far [subscription] has come over all [partitions] of its source: the sum, over each partition, of its
     * progress there. In a partition this node runs, the worker's [SubscriptionWorker.progress], which grows the
     * moment an entry completes, even above an entry still running; in the others, the saved checkpoint (else 0),
     * which another node moves forward. The sum changes when the subscription completed an entry: the snapshot
     * subscription's is the validator of the cached counts, so a count after a write this node
     * completed sees the write. Null when [subscription] is not registered.
     */
    suspend fun progress(subscription: String, partitions: Int): Long? {
        if (!directory.containsKey(subscription)) {
            return null
        }
        val workers = running?.workers
        var total = 0L
        for (partition in 0 until partitions) {
            total += workers?.get(subscription to partition)?.progress
                ?: checkpoints.load(group, subscription, partition)
                ?: 0L
        }
        return total
    }

    /**
     * Where [subscription] starts without a checkpoint: as the host overrides it, else as it says, where it joins the
     * stream ending after it when a subscription of [joined] other than it read the stream before.
     */
    private fun startOf(subscription: Subscription, joined: Set<String>): SubscriptionStart =
        options.starts[subscription.id]
            ?: SubscriptionStart.of(subscription.startFrom, joined = joined.any { it != subscription.name })

    /** The checkpoints saved in the group: of each subscription of its name that has read its stream. */
    internal suspend fun saved(): List<Checkpoint> = checkpoints.list(group)

    /**
     * The checkpoints in [saved] of an application's subscription the group no longer has, each with the entries parked
     * under the name, counted up to [maxParked]: what a subscription renamed, or removed, left behind, ordered by name.
     * Wow's own subscriptions (`wow.…`) are left out: those it ends, such as a finished rebuild's, are its own to keep.
     * The first start warns of them, and the operator reads them ([me.ahoo.wow.runtime.node.subscriptionsView]).
     */
    internal suspend fun orphans(saved: List<Checkpoint>, maxParked: Int): List<OrphanCheckpoints> {
        require(maxParked >= 1) { "At least one parked entry is counted." }
        return saved.filter { !directory.containsKey(it.subscription) && !it.subscription.startsWith(RESERVED) }
            .groupBy { it.subscription }
            .toSortedMap()
            .map { (name, positions) ->
                OrphanCheckpoints(group, name, positions.sortedBy { it.partition }, ledger.count(name, maxParked))
            }
    }

    /**
     * Warns of the [orphans] in [saved]: a subscription's name is its identity, so a renamed one starts again as its
     * start says; pinning its `@Name` to the old name keeps its position.
     */
    private suspend fun tellOrphans(saved: List<Checkpoint>) {
        val orphans = orphans(saved, ORPHAN_COUNT)
        if (orphans.isEmpty()) {
            return
        }
        val described = orphans.map { orphan ->
            val at = orphan.positions.joinToString { "partition ${it.partition} at ${it.position}" }
            val held = if (orphan.parked >= ORPHAN_COUNT) "$ORPHAN_COUNT or more" else "${orphan.parked}"
            "${orphan.name} ($at; $held parked)"
        }
        logger.warn {
            "Subscription group $group has checkpoints of subscriptions it no longer has: " +
                "${described.joinToString("; ")}. A renamed subscription starts again under its new name, as its " +
                "start says; to keep its position, pin its @Name to the old one. What is left under an old name " +
                "is not read again."
        }
    }

    /** The subscriptions of the group with a checkpoint: those that have read its stream. */
    private suspend fun joinedSubscriptions(): Set<String> =
        checkpoints.list(group).mapTo(HashSet()) { it.subscription }

    /**
     * Where each subscription stands in each partition this runtime runs on this node: its watermark,
     * the sequence up to which every entry is complete. Against the source's head it is how far a subscription, a
     * rebuild under a new name among them, has come. Empty while the runtime is stopped.
     */
    fun positions(): List<SubscriptionPosition> = running?.workers?.values?.map {
        SubscriptionPosition(it.subscription.name, it.partition, it.watermark)
    }.orEmpty()

    /**
     * The partitions this runtime's subscriptions have paused on a systemic failure, each a
     * subscription in a partition this node runs; empty while the runtime is stopped.
     */
    override fun paused(): List<PausedPartition> = running?.workers?.values?.mapNotNull { it.paused }.orEmpty()

    /**
     * The partitions this runtime's subscriptions throttle on backpressure, each a subscription in a
     * partition this node runs; empty while the runtime is stopped.
     */
    fun throttled(): List<ThrottledPartition> = running?.workers?.values?.mapNotNull { it.throttled }.orEmpty()

    private suspend fun workerOf(subscription: String, ref: AggregateRef): SubscriptionWorker? {
        val workers = running?.workers ?: return null
        return workers[subscription to source.partitionOf(ref)]
    }

    private suspend fun worker(
        run: Running,
        part: PartitionRun,
        subscription: Subscription,
        parked: Map<Int, Map<AggregateRef, Long>>,
        joined: Set<String>,
    ): SubscriptionWorker {
        val partition = part.partition
        val checkpoint = checkpoints.load(group, subscription.name, partition) ?: source
            .checkpointOf(startOf(subscription, joined), partition).also {
                // Saved at once, 0 included, so a restart before the first save does not move the start to a later end.
                checkpoints.save(group, subscription.name, partition, it)
            }
        return SubscriptionWorker(
            group = group,
            subscription = subscription,
            partition = partition,
            source = source,
            checkpoints = checkpoints,
            ledger = ledger,
            options = options,
            clock = clock,
            lanes = part.lanes,
            signals = signals,
            notifier = notifier,
            observer = observer,
            classification = classification,
            checkpoint = checkpoint,
            parked = parked[partition].orEmpty(),
        ).also {
            part.workers += it
            run.workers[subscription.name to partition] = it
        }
    }

    /**
     * [subscription]'s parked aggregates in [partitions], by partition, with the version each is parked from: the
     * ledger's entries of those partitions alone ([FailureLedger.due] at [ParkedAggregate.NEVER]).
     */
    private suspend fun parkedIn(
        subscription: Subscription,
        partitions: Collection<Int>,
    ): Map<Int, Map<AggregateRef, Long>> {
        val parked = HashMap<Int, HashMap<AggregateRef, Long>>()
        var after: AggregateRef? = null
        do {
            val page = ledger.due(subscription.name, partitions, ParkedAggregate.NEVER, after)
            page.forEach { parked.getOrPut(it.partition) { HashMap() }[it.ref] = source.ordinalOf(it) }
            after = page.lastOrNull()?.ref
        } while (page.size == FailureLedger.DEFAULT_PAGE)
        return parked
    }

    /**
     * Saves the checkpoints of [workers] that moved since their last save, in one call of the store (B.D diagnostics:
     * one round trip a tick, not one per subscription and partition); a failed save is retried on the next tick.
     */
    @Suppress("TooGenericExceptionCaught") // A failed save is retried on the next tick.
    private suspend fun flushQuietly(workers: Collection<SubscriptionWorker>) {
        val due = workers.mapNotNull { worker -> worker.unsaved()?.let { worker to it } }
        if (due.isEmpty()) {
            return
        }
        try {
            checkpoints.saveAll(group, due.map { it.second })
            due.forEach { (worker, checkpoint) -> worker.saved(checkpoint) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The watermarks stay ahead of the stored checkpoints until a save succeeds.
            logger.warn(failure) {
                "Failed to save the checkpoints of ${due.size} subscription partitions of group $group; " +
                    "retrying on the next tick."
            }
        }
    }

    /** Guarded by `lifecycle`, except [workers], which waits and the checkpoint ticker read from any thread. */
    private class Running(val root: Job, val scope: CoroutineScope) {
        val partitions = ConcurrentHashMap<Int, PartitionRun>()
        val workers = ConcurrentHashMap<Pair<String, Int>, SubscriptionWorker>()

        /** The subscriptions that had read the stream when the runtime started ([joinedSubscriptions]). */
        var joined: Set<String> = emptySet()
    }

    /** One partition being read: its shared tail and catch-up reads, and its subscriptions' lanes, stopped together. */
    private inner class PartitionRun(val partition: Int, run: Running) {
        val readersJob = SupervisorJob(run.root)
        val lanesJob = SupervisorJob(run.root)
        val lanes = CoroutineScope(run.scope.coroutineContext + lanesJob)
        val runner = PartitionRunner(
            partition,
            source,
            CoroutineScope(run.scope.coroutineContext + readersJob),
            options
        )
        val workers = ArrayList<SubscriptionWorker>()
    }
}

/**
 * Where [subscription] stands in [partition] of its source: every entry up to the sequence [watermark] is complete.
 */
@InternalWowApi
data class SubscriptionPosition(val subscription: String, val partition: Int, val watermark: Long)

/**
 * The checkpoints a subscription of [group] left behind: [name], a name the group no longer has, saved its [positions],
 * one per partition, and has [parked] entries under it, counted up to a limit. A checkpoint holds a subscription's name
 * alone, without its context.
 */
@InternalWowApi
data class OrphanCheckpoints(
    val group: String,
    val name: String,
    val positions: List<Checkpoint>,
    val parked: Int,
)

private val logger = KotlinLogging.logger {}

/** The prefix of Wow's own subscriptions' names, which the compiler reserves. */
private const val RESERVED = "wow."

/** How many entries parked under an old name a start counts at most. */
private const val ORPHAN_COUNT = 1_000
