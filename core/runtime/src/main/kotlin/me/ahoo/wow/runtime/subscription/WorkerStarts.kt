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

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.ImportCutover
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a [SubscriptionRuntime]'s subscriptions start in a partition of its [source]: each at its checkpoint in
 * [group], or, the first time, where its start puts it ([options]' overrides, else its own), saved at once; with its
 * parked aggregates there; and the partition's import [cutover], read once. It reads the store and holds the cut-over
 * of each partition; the runtime opens the workers.
 */
internal class WorkerStarts(
    private val group: String,
    private val source: EntrySource,
    private val checkpoints: CheckpointStore,
    private val options: SubscriptionOptions,
    /** The checkpoint group of the log's import cut-over; null for a topic. */
    private val cutover: String?,
) {
    /** Per partition, the sequence up to which the log holds imported history ([cutover]); 0 for none. */
    private val importedThrough = ConcurrentHashMap<Int, Long>()

    /** The cut-over of [partition], which [startsIn] read before its workers open: what [SubscriptionWorker] takes. */
    fun importedThroughOf(partition: Int): Long =
        checkNotNull(importedThrough[partition]) { "The cut-over of $partition is read first." }

    /**
     * Where each of [named] starts in [partition], read at once: its parked aggregates there, and its checkpoint,
     * or, for one that has none yet, where its start puts it, saved at once, 0 included, so a restart before the first
     * save does not move the start to a later end. Those first checkpoints are saved in one write. The partition's
     * cut-over row is read alongside, for its workers.
     */
    suspend fun startsIn(
        atStart: ParkedAtStart,
        joined: Set<String>,
        named: Collection<Subscription>,
        partition: Int
    ): Map<String, WorkerStart> {
        val starts = coroutineScope {
            launch { importedThrough(partition) }
            named.map { subscription ->
                async {
                    val parked = async { atStart.parkedIn(subscription, listOf(partition))[partition].orEmpty() }
                    workerStart(subscription, partition, joined, parked.await())
                }
            }.awaitAll()
        }
        val first = starts.filter { it.first }.map { Checkpoint.of(it.subscription.name, partition, it.checkpoint) }
        if (first.isNotEmpty()) {
            checkpoints.saveAll(group, first)
        }
        return starts.associateBy { it.subscription.name }
    }

    /**
     * Where [subscription] starts in [partition], with its [parked] aggregates there: at its checkpoint, or, without
     * one, where it starts ([startOf]) among the subscriptions [joined] before; [WorkerStart.first] then, for its
     * caller to save.
     */
    suspend fun workerStart(
        subscription: Subscription,
        partition: Int,
        joined: Set<String>,
        parked: Map<AggregateRef, Long>,
    ): WorkerStart {
        val saved = checkpoints.load(group, subscription.name, partition)
        val checkpoint = saved ?: source.checkpointOf(startOf(subscription, joined), partition)
        return WorkerStart(subscription, checkpoint, saved == null, parked)
    }

    /**
     * Where [subscription] starts without a checkpoint: as the host overrides it, else as it says, where it joins the
     * stream ending after it when a subscription of [joined] other than it read the stream before.
     */
    private fun startOf(subscription: Subscription, joined: Set<String>): SubscriptionStart =
        options.starts[subscription.id]
            ?: SubscriptionStart.of(subscription.startFrom, joined = joined.any { it != subscription.name })

    /** The subscriptions of the group with a checkpoint: those that have read its stream. */
    suspend fun joinedSubscriptions(): Set<String> =
        checkpoints.list(group).mapTo(HashSet()) { it.subscription }

    /**
     * The sequence up to which [partition] holds the history migrate-data imported: its [cutover] row, read once per
     * partition (the cut-over does not move once V10 runs); 0 when the context was never migrated.
     */
    private suspend fun importedThrough(partition: Int): Long {
        importedThrough[partition]?.let { return it }
        val through = cutover?.let { checkpoints.load(it, ImportCutover.SUBSCRIPTION, partition) } ?: 0L
        return importedThrough.putIfAbsent(partition, through) ?: through
    }
}

/**
 * Where [subscription] starts in a partition: at [checkpoint], its [first] when it had none there, with its [parked]
 * aggregates.
 */
internal class WorkerStart(
    val subscription: Subscription,
    val checkpoint: Long,
    val first: Boolean,
    val parked: Map<AggregateRef, Long>,
)
