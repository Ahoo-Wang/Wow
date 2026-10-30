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

package me.ahoo.wow.runtime.node

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.subscription.OrphanCheckpoints
import me.ahoo.wow.runtime.subscription.PausedPartition
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.ThrottledPartition
import me.ahoo.wow.spi.subscription.Checkpoint

/**
 * What an operator reads of the node's subscriptions, the built-in ones included, each by the group it runs in, and
 * of the checkpoints its groups hold under names they no longer have:
 *
 * - A subscription of a context's log ([SubscriptionView.Source.LOG]) stands in every partition its group saved a checkpoint
 *   of, read with one [me.ahoo.wow.spi.subscription.CheckpointStore.list] of the group. In a partition this node
 *   runs, its position is its live watermark, with the head [sample] read of the partition and whether the partition
 *   is paused or throttled.
 * - A subscription of a topic ([SubscriptionView.Source.TOPIC]: a context's command intake, another context's published
 *   topic) lists only the partitions this node runs that are paused or throttled.
 * - Its parked entries are [sample]'s count ([NodeSampler]), null when the sample has none.
 * - The orphans are each log group's and each joined topic's ([SubscriptionRuntime.orphans]: an application's names,
 *   not Wow's own), their parked entries counted up to [maxParked], as the sampler counts. A node registers its
 *   application's subscriptions as it starts, so it tells them only once it runs: before, every name would be one.
 *
 * A store that fails fails the read ([me.ahoo.wow.spi.StorageUnavailableException]).
 */
@InternalWowApi
suspend fun ServiceNode.subscriptionsView(sample: NodeSample, maxParked: Int): SubscriptionsView {
    val subscriptions = ArrayList<SubscriptionView>()
    val orphans = ArrayList<OrphanCheckpoints>()
    val registered = state == ServiceNode.State.RUNNING
    contexts.values.forEach { context ->
        context.logGroups.forEach { group ->
            val runtime = group.subscriptions
            val saved = runtime.saved()
            subscriptions += logViews(runtime, saved, sample)
            if (registered) {
                orphans += runtime.orphans(saved, maxParked)
            }
        }
        context.intake?.let { subscriptions += topicViews(it, sample) }
    }
    topicSubscriptions.values.forEach { topic ->
        val runtime = topic.runtime
        subscriptions += topic.subscriptions.map { subscription ->
            SubscriptionView(
                subscription.id,
                topic.group.name,
                SubscriptionView.Source.TOPIC,
                sample.parked[ParkedSubscription(topic.group.name, subscription.name)],
                runtime?.let { topicPartitions(it, subscription.name) }.orEmpty(),
            )
        }
        runtime?.takeIf { registered }?.let { orphans += it.orphans(it.saved(), maxParked) }
    }
    return SubscriptionsView(
        node,
        subscriptions.sortedWith(compareBy<SubscriptionView> { it.group }.thenBy { it.id }),
        orphans.sortedWith(compareBy<OrphanCheckpoints> { it.group }.thenBy { it.name }),
    )
}

/** The subscriptions of a log group's [runtime], each in the partitions of [saved] and those this node runs. */
private fun logViews(
    runtime: SubscriptionRuntime,
    saved: List<Checkpoint>,
    sample: NodeSample
): List<SubscriptionView> {
    val checkpoints = saved.associate { (it.subscription to it.partition) to it.position }
    val watermarks = runtime.positions().associate { (it.subscription to it.partition) to it.watermark }
    val paused = runtime.paused().associateBy { it.subscription to it.partition }
    val throttled = runtime.throttled().associateBy { it.subscription to it.partition }
    return runtime.subscriptionNames.mapNotNull { name ->
        val context = runtime.contextOf(name) ?: return@mapNotNull null
        val partitions = (checkpoints.keys + watermarks.keys).filter { it.first == name }.map { it.second }.sorted()
        val views = partitions.map { partition ->
            val key = name to partition
            when (val watermark = watermarks[key]) {
                null -> PartitionView(partition, checkpoints.getValue(key))
                else -> PartitionView(
                    partition,
                    watermark,
                    runs = true,
                    head = sample.heads[runtime.group to partition],
                    paused = paused[key],
                    throttled = throttled[key],
                )
            }
        }
        SubscriptionView(
            SubscriptionId(context, name),
            runtime.group,
            SubscriptionView.Source.LOG,
            sample.parked[ParkedSubscription(runtime.group, name)],
            views,
        )
    }
}

/** The subscriptions of a topic's [runtime], each in the partitions this node runs that are held back. */
private fun topicViews(runtime: SubscriptionRuntime, sample: NodeSample): List<SubscriptionView> =
    runtime.subscriptionNames.mapNotNull { name ->
        val context = runtime.contextOf(name) ?: return@mapNotNull null
        SubscriptionView(
            SubscriptionId(context, name),
            runtime.group,
            SubscriptionView.Source.TOPIC,
            sample.parked[ParkedSubscription(runtime.group, name)],
            topicPartitions(runtime, name),
        )
    }

/** The partitions of [subscription] that [runtime] runs on this node and holds back: paused or throttled. */
private fun topicPartitions(runtime: SubscriptionRuntime, subscription: String): List<PartitionView> {
    val paused = runtime.paused().filter { it.subscription == subscription }.associateBy { it.partition }
    val throttled = runtime.throttled().filter { it.subscription == subscription }.associateBy { it.partition }
    val held = paused.keys + throttled.keys
    return runtime.positions().filter { it.subscription == subscription && it.partition in held }
        .sortedBy { it.partition }
        .map {
            PartitionView(
                it.partition,
                it.watermark,
                true,
                paused = paused[it.partition],
                throttled = throttled[it.partition]
            )
        }
}

/**
 * The node's [subscriptions], ordered by group and then by subscription, and the [orphans] of its groups, ordered the
 * same way ([ServiceNode.subscriptionsView]).
 */
@InternalWowApi
data class SubscriptionsView(
    val node: String,
    val subscriptions: List<SubscriptionView>,
    val orphans: List<OrphanCheckpoints>,
)

/**
 * The subscription [id], run in [group] over its [source], with [parked] entries (counted up to the sampler's limit;
 * null when not counted) and where it stands in its [partitions].
 */
@InternalWowApi
data class SubscriptionView(
    val id: SubscriptionId,
    val group: String,
    val source: Source,
    val parked: Int?,
    val partitions: List<PartitionView>,
) {
    /** What a subscription reads: its context's log, or a topic. */
    @InternalWowApi
    enum class Source { LOG, TOPIC }
}

/**
 * Where a subscription stands in [partition]: every entry up to [position] is complete. In a partition this node
 * [runs], [position] is its live watermark, [head] the head the last sample read (null when it read none), and the
 * partition may be [paused] on a systemic failure or [throttled] by backpressure; in another, [position] is its saved
 * checkpoint, which the node that runs it moves.
 */
@InternalWowApi
data class PartitionView(
    val partition: Int,
    val position: Long,
    val runs: Boolean = false,
    val head: Long? = null,
    val paused: PausedPartition? = null,
    val throttled: ThrottledPartition? = null,
) {
    /** How many entries of the log the subscription is behind [head]; null without one. */
    val lag: Long? get() = head?.let { (it - position).coerceAtLeast(0) }
}
