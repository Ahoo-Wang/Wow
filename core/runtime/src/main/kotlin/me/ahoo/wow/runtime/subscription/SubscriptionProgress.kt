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

import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.subscription.CheckpointStore

/**
 * How far the subscriptions of a [SubscriptionRuntime]'s [group] have come: in a partition this node runs, the
 * running worker's, else the checkpoint saved. Its runtime's `directory` names the subscriptions registered, and
 * `workers` gives those of its run, by subscription and partition; null while it is stopped.
 */
internal class SubscriptionProgress(
    private val group: String,
    private val source: EntrySource,
    private val checkpoints: CheckpointStore,
    private val ledger: FailureLedger,
    private val directory: Map<String, Subscription>,
    private val workers: () -> Map<Pair<String, Int>, SubscriptionWorker>?,
) {
    /**
     * Whether [subscription] is past [stream] for good: its progress in the stream's partition (the
     * running worker's watermark, else the saved checkpoint) is at or past the stream's position, and the stream's
     * aggregate is not parked.
     */
    suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean {
        val id = directory[subscription]?.takeIf { it.filter.context == stream.ref.type.context }?.id ?: return false
        val position = source.positionOf(stream.ref, stream.version) ?: return false
        val progress = workers()?.get(subscription to position.partition)?.watermark
            ?: checkpoints.load(group, subscription, position.partition)
            ?: 0L
        return progress >= position.sequence && !ledger.isParked(id, stream.ref)
    }

    /**
     * How far [subscription] has come over all [partitions] of its source: the sum, over each partition, of its
     * progress there. In a partition this node runs, the worker's [SubscriptionWorker.progress], which grows the
     * moment an entry completes, even above an entry still running; in the others, the saved checkpoint (else 0),
     * which another node moves forward. The sum changes when the subscription completed an entry: the snapshot
     * subscription's is the validator of the cached counts, so a count after a write this node
     * completed sees the write. Null when [subscription] is not registered.
     *
     * The checkpoints of the partitions not run here are read in one call of the store, the subscription's list, not
     * one call each (RT-15): a count query asks this before it may use a cached count. None is read when every
     * partition runs here.
     */
    suspend fun progress(subscription: String, partitions: Int): Long? {
        if (!directory.containsKey(subscription)) {
            return null
        }
        val workers = workers()
        val here = (0 until partitions).map { workers?.get(subscription to it)?.progress }
        val saved = if (here.any { it == null }) {
            checkpoints.list(group, subscription).associate { it.partition to it.position }
        } else {
            emptyMap()
        }
        return here.withIndex().sumOf { (partition, progress) -> progress ?: saved[partition] ?: 0L }
    }

    /**
     * Where each subscription stands in each partition this runtime runs on this node: its watermark,
     * the sequence up to which every entry is complete, and the entries it took above it. Against the source's head it
     * is how far a subscription, a rebuild under a new name among them, has come. Empty while the runtime is stopped.
     * Reads the workers' own fields, with no lock.
     */
    fun positions(): List<SubscriptionPosition> = workers()?.values?.map {
        SubscriptionPosition(it.subscription.name, it.partition, it.watermark, it.inFlight)
    }.orEmpty()
}
