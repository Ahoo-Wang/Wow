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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.runtime.health.launchLoop
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.ImportCutover
import me.ahoo.wow.spi.subscription.SubscriptionId
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
    /**
     * The checkpoint group of the log's import cut-over ([ImportCutover.groupOf]): the entries up to it are handed
     * over as imported ([SubscribedEntry.imported]). Null for a runtime that reads a topic; a context that was never
     * migrated has no row in it, so none of its entries is.
     */
    private val cutover: String? = null,
    /**
     * The node's permits of partitions reading behind their head ([SubscriptionOptions.maxCatchUps]), which every
     * runtime of the node shares; null for no bound.
     */
    private val catchUps: Semaphore? = null,
    /**
     * Where its lanes record the fatal error that stops one, and the handler of what escapes its coroutines: its
     * node's ([NodeFatals]).
     */
    private val fatals: NodeFatals = NodeFatals.NONE,
) : SubscriptionDirectory, SubscriptionPauses {
    /** The runtime of a context's own [log]; the internal constructor takes any source, a topic's among them. */
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
        cutover: String? = null,
        catchUps: Semaphore? = null,
        fatals: NodeFatals = NodeFatals.NONE,
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
        cutover = cutover,
        catchUps = catchUps,
        fatals = fatals,
    )

    private val lifecycle = Mutex()
    private val retries = ParkedRetries(group, ledger, options.parked, clock, notifier)
    private val subscriptions = LinkedHashMap<String, Subscription>()

    /** The registered subscriptions by name, read by waits from any thread. */
    private val directory = ConcurrentHashMap<String, Subscription>()

    /** The checkpoints the group's subscriptions left behind under a name it no longer has. */
    private val orphanCheckpoints = GroupOrphans(group, ledger, directory)

    /** Where each subscription starts in a partition, and the partition's cut-over. */
    private val workerStarts = WorkerStarts(group, source, checkpoints, options, cutover)

    @Volatile
    private var running: Running? = null

    /** How far the subscriptions have come: the running workers', else the saved checkpoints. */
    private val progressOf = SubscriptionProgress(group, source, checkpoints, ledger, directory) { running?.workers }

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
        val scope = CoroutineScope(context + root + fatals.contextOf(Component.SUBSCRIPTION, group))
        val run = Running(root, scope, ParkedAtStart(source, ledger, scope))
        var started = false
        try {
            // Who read the stream before this start: a subscription that starts where it joins reads it from the
            // beginning only when none did, whichever partition or subscription starts first.
            val saved = checkpoints.list(group)
            run.joined = saved.mapTo(HashSet()) { it.subscription }
            if (tellsOrphans && !orphansTold) {
                orphansTold = true
                orphanCheckpoints.tellOrphans(saved)
            }
            partitions.forEach {
                open(
                    run,
                    it,
                    workerStarts.startsIn(run.parked, run.joined, subscriptions.values, it)
                )
            }
            started = true
        } finally {
            if (!started) {
                root.cancel()
            }
        }
        run.scope.launchLoop("checkpoints") {
            delay(options.checkpointInterval)
            flushQuietly(run.workers.values)
        }
        run.scope.launchLoop("parked scan") {
            delay(options.parked.scanInterval)
            scanQuietly(run)
        }
        running = run
    }

    /** Runs the parked entries due in the partitions [run] reads ([ParkedRetries]); a failed scan waits for the next. */
    @Suppress("TooGenericExceptionCaught") // A failed read of the ledger is retried on the next scan.
    private suspend fun scanQuietly(run: Running) {
        try {
            // A paused partition's entries wait: their retries would fail the same way, and the ledger may be down. A
            // partition held back reads none yet ([startPartition]).
            val reading = run.partitions.filterValues { it.reading }.keys
            retries.scan(run.scope, directory.values.map { it.id }.sorted(), reading) { subscription, ref ->
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
     * acquired it. Nothing happens when it is read already; a start of it already running is waited for.
     *
     * What the start reads, each subscription's parked aggregates and checkpoint and the partition's cut-over row, is
     * read at once and outside the runtime's lifecycle mutex, so the partitions a reconcile starts together do not
     * queue behind each other's round trips; the checkpoints its subscriptions start at for the first time are saved
     * in one write. Only opening the partition's workers holds the mutex.
     *
     * With [reads], the partition reads nothing until that job completes: neither its entries nor its parked retries.
     * Its subscriptions stand at their checkpoints meanwhile, and their lag counts what waits. A node holds back the
     * partitions its first reconcile starts this way ([me.ahoo.wow.runtime.cluster.PartitionOwnership.start]): the
     * backlog the first of them would read at once, a history migrate-data imported say, would slow down the starts of
     * the others.
     *
     * A stop of [partition] under way ([stopPartition]) ends first: the node never reads a partition twice.
     */
    suspend fun startPartition(partition: Int, reads: Job? = null) {
        requirePartition(partition)
        val begun = CompletableDeferred<Unit>()
        val (run, named) = claim(partition, begun) ?: return
        try {
            val starts = workerStarts.startsIn(run.parked, run.joined, named, partition)
            lifecycle.withLock {
                val current = checkNotNull(running) { "Subscription group $group is not running." }
                if (current.partitions.containsKey(partition)) {
                    return@withLock
                }
                // A subscription added meanwhile, or a runtime started again, reads its start here.
                val known = if (current === run) starts else emptyMap()
                val missing = subscriptions.values.filter { it.name !in known }
                open(
                    current,
                    partition,
                    known + workerStarts.startsIn(current.parked, current.joined, missing, partition),
                    reads
                )
            }
        } finally {
            run.starting.remove(partition, begun)
            begun.complete(Unit)
        }
    }

    /**
     * Claims the start of [partition] for [begun]: the run and the subscriptions to start, or null when the partition
     * is read already or a start of it running is done. A stop of it under way is waited for, then asked again.
     */
    private suspend fun claim(partition: Int, begun: CompletableDeferred<Unit>): Pair<Running, List<Subscription>>? {
        while (true) {
            val stopping = lifecycle.withLock {
                val current = checkNotNull(running) { "Subscription group $group is not running." }
                current.stopping[partition] ?: when {
                    current.partitions.containsKey(partition) -> return null
                    current.starting.putIfAbsent(partition, begun) != null -> null
                    else -> return current to subscriptions.values.toList()
                }
            }
            if (stopping == null) {
                running?.starting?.get(partition)?.join()
                return null
            }
            stopping.join()
        }
    }

    /**
     * Stops reading [partition] as [stop] does for all of them: waits at most [SubscriptionOptions.stopTimeout] for
     * its entries in flight and saves its checkpoints, so the node can hand the partition off. The
     * other partitions keep running. Nothing happens when it is not read; a start of it running is waited for first,
     * a stop of it under way waited for instead.
     *
     * The lifecycle mutex is held only to take the partition out of the run: its drain and its save run outside it,
     * so the partitions a hand-off stops together drain together (RT-14), and a start of the partition waits for them.
     */
    suspend fun stopPartition(partition: Int) {
        running?.starting?.get(partition)?.join()
        val ended = CompletableDeferred<Unit>()
        var other: Job? = null
        val claimed = lifecycle.withLock {
            val run = running ?: return
            val part = run.partitions.remove(partition)
            if (part == null) {
                other = run.stopping[partition]
                null
            } else {
                run.stopping[partition] = ended
                run to part
            }
        }
        if (claimed == null) {
            other?.join()
            return
        }
        val (run, part) = claimed
        try {
            halt(listOf(part))
        } finally {
            part.workers.forEach { run.workers.remove(it.subscription.name to partition) }
            run.stopping.remove(partition, ended)
            ended.complete(Unit)
        }
    }

    private fun requirePartition(partition: Int) = source.requirePartition(partition)

    private fun open(run: Running, partition: Int, starts: Map<String, WorkerStart>, reads: Job? = null) {
        val part = PartitionRun(partition, run, reads, source, options, catchUps)
        run.partitions[partition] = part
        val workers = subscriptions.values.map { worker(run, part, starts.getValue(it.name)) }
        part.runner.start(workers)
    }

    /**
     * Registers [subscription]. On a running runtime it starts at once in every partition: it catches up from its
     * checkpoint (or as [Subscription.startFrom] says) on its own read, then joins the shared tail.
     */
    suspend fun add(subscription: Subscription): Unit = lifecycle.withLock {
        register(subscription)
        val run = running ?: return@withLock
        val parked = run.parked.parkedIn(subscription, run.partitions.keys)
        val joined = workerStarts.joinedSubscriptions()
        for (part in run.partitions.values) {
            val start = workerStarts.workerStart(subscription, part.partition, joined, parked[part.partition].orEmpty())
            if (start.first) {
                checkpoints.save(group, subscription.name, part.partition, start.checkpoint)
            }
            part.runner.attach(worker(run, part, start))
        }
    }

    /**
     * Stops reading, waits at most [SubscriptionOptions.stopTimeout] for the entries in flight, abandons the rest,
     * and saves every checkpoint. An abandoned entry is above the saved checkpoint and is handled after a restart.
     * The partitions stopping on their own ([stopPartition]) end before the run does: no checkpoint is saved after it.
     */
    suspend fun stop(): Unit = lifecycle.withLock {
        val run = running ?: return@withLock
        running = null
        try {
            halt(run.partitions.values.toList())
        } finally {
            try {
                run.stopping.values.forEach { it.join() }
            } finally {
                run.root.cancelAndJoin()
            }
        }
    }

    /**
     * Stops reading [parts], drains them within the stop timeout, abandons the rest and saves their checkpoints, in one
     * call of the store a partition, each tried whatever the others' came to; the first failure is thrown.
     */
    @Suppress("TooGenericExceptionCaught") // Kept, and thrown once every partition's save was tried.
    private suspend fun halt(parts: List<PartitionRun>) {
        parts.forEach { it.readersJob.cancelAndJoin() }
        val workers = parts.flatMap { it.workers }
        withTimeoutOrNull(options.stopTimeout) { workers.forEach { it.awaitDrained() } }
        parts.forEach { it.lanesJob.cancelAndJoin() }
        workers.forEach { it.abandon() }
        var failure: Exception? = null
        for (part in parts) {
            try {
                save(part.workers)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                failure = failure ?: failed
            }
        }
        failure?.let { throw it }
    }

    /**
     * Handles [subscription]'s parked aggregate [ref] again from its parked version, then resolves its ledger
     * entry; the aggregate's new entries are handled after it, in version order (see [SubscriptionWorker.retry]).
     * The runtime must be running the aggregate's partition.
     *
     * @return true when the aggregate is no longer parked; false when it was not parked or failed again.
     */
    suspend fun retry(subscription: String, ref: AggregateRef): Boolean {
        val worker = checkNotNull(workerOf(subscription, ref)) {
            "Subscription $subscription of group $group is not running the partition of $ref here."
        }
        return worker.retry(ref)
    }

    /**
     * Resolves [subscription]'s parked aggregate [ref] without handling what was skipped; its new entries are
     * handled from now on.
     *
     * @return true when the aggregate was parked; false when it was not, or a retry of it is running.
     */
    suspend fun skip(subscription: String, ref: AggregateRef): Boolean {
        val id = requireNotNull(subscriptions[subscription]) { "No subscription $subscription in group $group." }.id
        val worker = workerOf(subscription, ref) ?: return ledger.resolveAnywhere(id, ref) != null
        return worker.skip(ref)
    }

    /** The subscriptions registered, by their contexts and names, read from any thread. */
    val subscriptionIds: Set<SubscriptionId> get() = directory.values.mapTo(HashSet()) { it.id }

    /**
     * Whether the runtime runs [subscription] in [ref]'s partition here now: [retry] and [skip] act on the aggregate
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

    /** Whether [subscription] is past [stream] for good ([SubscriptionProgress.hasHandled]). */
    override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean =
        progressOf.hasHandled(subscription, stream)

    /** How far [subscription] has come over all [partitions] of its source ([SubscriptionProgress.progress]). */
    suspend fun progress(subscription: String, partitions: Int): Long? = progressOf.progress(subscription, partitions)

    /** The checkpoints saved in the group: of each subscription of its name that has read its stream. */
    internal suspend fun saved(): List<Checkpoint> = checkpoints.list(group)

    /** [GroupOrphans.orphans] of the group's [saved] checkpoints. */
    internal suspend fun orphans(saved: List<Checkpoint>, maxParked: Int): List<OrphanCheckpoints> =
        orphanCheckpoints.orphans(saved, maxParked)

    /** Where each subscription stands in each partition this runtime runs here ([SubscriptionProgress.positions]). */
    fun positions(): List<SubscriptionPosition> = progressOf.positions()

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

    /** [start]'s subscription's worker in [part], at its checkpoint: its first one saved already. */
    private fun worker(run: Running, part: PartitionRun, start: WorkerStart): SubscriptionWorker {
        val partition = part.partition
        val subscription = start.subscription
        return SubscriptionWorker(
            group = group,
            subscription = subscription,
            partition = partition,
            source = source,
            ledger = ledger,
            options = options,
            clock = clock,
            lanes = part.lanes,
            signals = signals,
            notifier = notifier,
            observer = observer,
            classification = classification,
            checkpoint = start.checkpoint,
            parked = start.parked,
            importedThrough = workerStarts.importedThroughOf(partition),
            fatals = fatals,
        ).also {
            part.workers += it
            run.workers[subscription.name to partition] = it
        }
    }

    /**
     * Saves the checkpoints of [workers] that moved since their last save, in one call of the store (B.D diagnostics:
     * one round trip a tick, not one per subscription and partition); a failed save is retried on the next tick.
     */
    @Suppress("TooGenericExceptionCaught") // A failed save is retried on the next tick.
    private suspend fun flushQuietly(workers: Collection<SubscriptionWorker>) {
        try {
            save(workers)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The watermarks stay ahead of the stored checkpoints until a save succeeds.
            logger.warn(failure) {
                "Failed to save the checkpoints of the subscription partitions of group $group; retrying on the next tick."
            }
        }
    }

    /** Saves the checkpoints of [workers] that moved since their last save, in one call of the store. */
    private suspend fun save(workers: Collection<SubscriptionWorker>) {
        val due = workers.mapNotNull { worker -> worker.saves.unsaved()?.let { worker to it } }
        if (due.isNotEmpty()) {
            checkpoints.saveAll(group, due.map { it.second })
            due.forEach { (worker, checkpoint) -> worker.saves.saved(checkpoint) }
        }
    }
}

/**
 * Where [subscription] stands in [partition] of its source: every entry up to the sequence [watermark] is complete,
 * and [inFlight] entries above it are taken and not yet below it, the window its in-flight bound
 * ([SubscriptionOptions.maxInFlight]) limits.
 */
@InternalWowApi
data class SubscriptionPosition(val subscription: String, val partition: Int, val watermark: Long, val inFlight: Int)

private val logger = KotlinLogging.logger {}
