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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.CheckpointStore
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Runs the subscriptions of one subscription [group] over the partitions this node owns (design 04 §1–§3).
 *
 * Which partitions those are is decided outside (leases, roadmap 4.2) and passed to [start]. For each partition
 * the runtime reads the log once and fans the entries out to every subscription ([PartitionRunner]); each
 * subscription keeps its own checkpoint per partition, runs one aggregate's entries in version order and different
 * aggregates in parallel, and parks an aggregate whose handler keeps failing ([SubscriptionWorker]).
 *
 * Duplicates are possible above a checkpoint (an entry handled but not yet saved when the node stopped or crashed),
 * never below it, so handlers are idempotent by (`ref`, `version`).
 */
class SubscriptionRuntime(
    val group: String,
    private val log: EventLog,
    private val checkpoints: CheckpointStore,
    private val ledger: FailureLedger,
    subscriptions: Collection<Subscription> = emptyList(),
    private val options: SubscriptionOptions = SubscriptionOptions(),
    private val clock: Clock = Clock.systemUTC(),
    private val context: CoroutineContext = Dispatchers.Default,
) {
    private val lifecycle = Mutex()
    private val subscriptions = LinkedHashMap<String, Subscription>()

    @Volatile
    private var running: Running? = null

    init {
        require(group.isNotBlank()) { "A subscription group needs a name." }
        subscriptions.forEach(::register)
    }

    private fun register(subscription: Subscription) {
        require(subscriptions.putIfAbsent(subscription.name, subscription) == null) {
            "Subscription ${subscription.name} is already registered in group $group."
        }
    }

    /** Whether the runtime is running. */
    val isRunning: Boolean get() = running != null

    /** Starts reading [partitions], each subscription from its checkpoint. */
    suspend fun start(partitions: Set<Int>): Unit = lifecycle.withLock {
        check(running == null) { "Subscription group $group is already running." }
        require(partitions.all { it in 0 until log.partitions }) { "The log has ${log.partitions} partitions." }
        val root = SupervisorJob()
        val run = Running(partitions, root, CoroutineScope(context + root))
        var started = false
        try {
            val parked = subscriptions.values.associate { it.name to parkedByPartition(it) }
            for (partition in partitions) {
                val workers = subscriptions.values.map { worker(run, it, partition, parked.getValue(it.name)) }
                PartitionRunner(partition, log, run.readers, options).also {
                    run.runners[partition] = it
                    it.start(workers)
                }
            }
            started = true
        } finally {
            if (!started) {
                root.cancel()
            }
        }
        run.scope.launch {
            while (true) {
                delay(options.checkpointInterval)
                run.workers.values.forEach { flushQuietly(it) }
            }
        }
        running = run
    }

    /**
     * Registers [subscription]. On a running runtime it starts at once in every partition: it catches up from its
     * checkpoint (or as [Subscription.startFrom] says) on its own read, then joins the shared tail.
     */
    suspend fun add(subscription: Subscription): Unit = lifecycle.withLock {
        register(subscription)
        val run = running ?: return@withLock
        val parked = parkedByPartition(subscription)
        for (partition in run.partitions) {
            run.runners.getValue(partition).attach(worker(run, subscription, partition, parked))
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
            run.readersJob.cancelAndJoin()
            withTimeoutOrNull(options.stopTimeout) { run.workers.values.forEach { it.awaitDrained() } }
            run.lanesJob.cancelAndJoin()
            run.workers.values.forEach { it.abandon() }
            run.workers.values.forEach { it.flush() }
        } finally {
            run.root.cancelAndJoin()
        }
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

    private fun workerOf(subscription: String, ref: AggregateRef): SubscriptionWorker? =
        running?.workers?.get(subscription to Partitioner.partitionOf(ref, log.partitions))

    private suspend fun worker(
        run: Running,
        subscription: Subscription,
        partition: Int,
        parked: Map<Int, Map<AggregateRef, Int>>,
    ): SubscriptionWorker {
        val checkpoint = checkpoints.load(group, subscription.name, partition) ?: when (subscription.startFrom) {
            StartFrom.BEGINNING -> 0L
            // Saved at once, so a restart before the first entry does not move the start to a later end.
            StartFrom.END -> endOf(partition).also {
                if (it > 0) {
                    checkpoints.save(group, subscription.name, partition, it)
                }
            }
        }
        return SubscriptionWorker(
            group = group,
            subscription = subscription,
            partition = partition,
            log = log,
            checkpoints = checkpoints,
            ledger = ledger,
            options = options,
            clock = clock,
            lanes = run.lanes,
            checkpoint = checkpoint,
            parked = parked[partition].orEmpty(),
        ).also { run.workers[subscription.name to partition] = it }
    }

    /** [subscription]'s parked aggregates by partition, with the version each is parked from. */
    private suspend fun parkedByPartition(subscription: Subscription): Map<Int, Map<AggregateRef, Int>> {
        val parked = HashMap<Int, HashMap<AggregateRef, Int>>()
        var after: AggregateRef? = null
        do {
            val page = ledger.list(subscription.name, after)
            page.forEach {
                parked.getOrPut(Partitioner.partitionOf(it.ref, log.partitions)) { HashMap() }[it.ref] = it.fromVersion
            }
            after = page.lastOrNull()?.ref
        } while (page.size == FailureLedger.DEFAULT_PAGE)
        return parked
    }

    /**
     * The sequence of the last entry of [partition] now: the log has no call for it, so its tail is read until it
     * stays quiet for [SubscriptionOptions.endProbe]. It runs once per subscription and partition, when a
     * subscription starting from the end has no checkpoint yet.
     */
    private suspend fun endOf(partition: Int): Long = coroutineScope {
        val sequences = Channel<Long>(Channel.CONFLATED)
        val reader = launch { log.tail(partition).collect { sequences.send(it.position.sequence) } }
        var end = 0L
        while (true) {
            end = withTimeoutOrNull(options.endProbe) { sequences.receive() } ?: break
        }
        reader.cancel()
        end
    }

    @Suppress("TooGenericExceptionCaught") // A failed save is retried on the next tick.
    private suspend fun flushQuietly(worker: SubscriptionWorker) {
        try {
            worker.flush()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The watermark stays ahead of the stored checkpoint until a save succeeds.
        }
    }

    private class Running(val partitions: Set<Int>, val root: Job, val scope: CoroutineScope) {
        val readersJob = SupervisorJob(root)
        val lanesJob = SupervisorJob(root)
        val readers = CoroutineScope(scope.coroutineContext + readersJob)
        val lanes = CoroutineScope(scope.coroutineContext + lanesJob)
        val runners = HashMap<Int, PartitionRunner>()
        val workers = ConcurrentHashMap<Pair<String, Int>, SubscriptionWorker>()
    }
}
