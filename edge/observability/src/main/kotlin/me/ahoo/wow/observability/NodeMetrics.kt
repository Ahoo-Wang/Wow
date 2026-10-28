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

package me.ahoo.wow.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.node.NodeSample
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.pauses
import me.ahoo.wow.runtime.node.throttled
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The gauges of a [node] that are read, not heard, set in [meters] from each of the node's samples ([sampler]):
 *
 * - [WowMetrics.PARKED_AGGREGATES]: how many entries each subscription of each group has parked, as the node's ledgers
 *   list them ([ServiceNode.parked]), counted up to the sampler's cap (`wow.node.max-parked`): a count past it reads as
 *   the cap, so a flood of parked entries costs one bounded read per sample, shared with the node's health. The ledgers are the service's, shared by its
 *   nodes, so one node reports them ([reportsParked]): the one that runs partition 0 of the service's first log group
 *   (by context name), or every node of a service without one. Summed across nodes, the count is the service's once.
 * - [WowMetrics.POSITION], [WowMetrics.BACKLOG] and [WowMetrics.PROGRESS]: for each subscription of each log group,
 *   in each partition it runs on this node ([me.ahoo.wow.runtime.subscription.SubscriptionRuntime.positions]), its
 *   watermark, the entries of the log after it, and the watermark over the log's head: how far a rebuild (a
 *   subscription under a new name, from the beginning) has come.
 * - [WowMetrics.PAUSED_PARTITIONS] and [WowMetrics.THROTTLED_PARTITIONS]: for each subscription of each log group
 *   that runs here, and each intake or other context's topic that has one (`ServiceNode.pauses`,
 *   `ServiceNode.throttled`), how many of its partitions on this node it has paused on a systemic failure,
 *   or throttles on backpressure: 0 while it runs them all, so one series per subscription,
 *   not one per partition.
 *
 * - [WowMetrics.EXECUTOR_IN_FLIGHT], [WowMetrics.EXECUTOR_LANES] and [WowMetrics.APPEND_QUEUE_DEPTH], per context: the
 *   commands in its executor, the aggregates with commands in flight, and the appends queued on its partitions,
 *   which show a store slowing down before the node refuses commands as busy.
 *
 * A gauge a sample no longer takes (a partition handed off to another node, the parked count of a node that no
 * longer reports it) is removed from [meters], so it does not report its last value forever.
 *
 * [start] starts the sampling; [close] stops it when the sampler is its own (one given to it is closed by its owner).
 */
@InternalWowApi
class NodeMetrics(
    private val node: ServiceNode,
    private val meters: MeterRegistry,
    /** The node's sampler, which its health may read too; one of its own when null. */
    sampler: NodeSampler? = null,
) : AutoCloseable {
    private val ownsSampler = sampler == null
    private val sampler: NodeSampler = sampler ?: NodeSampler(node)
    private val gauges = ConcurrentHashMap<Pair<String, Tags>, Pair<AtomicReference<Double>, Gauge>>()

    init {
        this.sampler.listen { sample -> sampled(sample) }
    }

    /** Starts sampling, once the node has started. Starting twice does nothing. */
    fun start() {
        sampler.start()
    }

    /** Takes one sample of the node ([NodeSampler.sample]), which sets every gauge. */
    suspend fun sample() {
        sampler.sample()
    }

    /** Sets every gauge from [sample], and removes the gauges it no longer takes. */
    private fun sampled(sample: NodeSample) {
        val taken = HashSet<Pair<String, Tags>>()
        if (reportsParked()) {
            sample.parked.forEach { (subscription, count) ->
                taken += gauge(
                    WowMetrics.PARKED_AGGREGATES,
                    WowMetrics.PARKED_AGGREGATES.tagsOf(subscription.group, subscription.name),
                    count.toDouble(),
                )
            }
        }
        node.contexts.forEach { (name, context) ->
            val tags = WowMetrics.EXECUTOR_IN_FLIGHT.tagsOf(name)
            taken += gauge(WowMetrics.EXECUTOR_IN_FLIGHT, tags, context.executor.commandsInFlight.toDouble())
            taken += gauge(WowMetrics.EXECUTOR_LANES, tags, context.executor.activeLanes.toDouble())
            taken += gauge(WowMetrics.APPEND_QUEUE_DEPTH, tags, context.log.queued.toDouble())
        }
        val paused = node.pauses.paused().groupingBy { subscriptionTags(it.group, it.subscription) }.eachCount()
        val throttled = node.throttled.groupingBy { subscriptionTags(it.group, it.subscription) }.eachCount()
        val subscriptions = HashSet<Tags>(paused.keys + throttled.keys)
        node.contexts.values.flatMap { it.logGroups }.forEach { group ->
            val runtime = group.subscriptions
            runtime.positions().forEach { position ->
                val tags = WowMetrics.POSITION.tagsOf(
                    runtime.group,
                    position.subscription,
                    position.partition.toString()
                )
                taken += gauge(WowMetrics.POSITION, tags, position.watermark.toDouble())
                // A head the sample could not read leaves the backlog and progress out, not wrong.
                sample.heads[runtime.group to position.partition]?.let { head ->
                    taken += gauge(WowMetrics.BACKLOG, tags, (head - position.watermark).coerceAtLeast(0).toDouble())
                    taken += gauge(WowMetrics.PROGRESS, tags, progress(position.watermark, head))
                }
                subscriptions += subscriptionTags(runtime.group, position.subscription)
            }
        }
        // The intakes and other contexts' topics have no position here: they count while they pause or throttle.
        subscriptions.forEach { tags ->
            taken += gauge(WowMetrics.PAUSED_PARTITIONS, tags, (paused[tags] ?: 0).toDouble())
            taken += gauge(WowMetrics.THROTTLED_PARTITIONS, tags, (throttled[tags] ?: 0).toDouble())
        }
        (gauges.keys - taken).forEach { key -> gauges.remove(key)?.let { (_, gauge) -> meters.remove(gauge) } }
    }

    /**
     * Whether this node reports the service's parked entries: it runs partition 0 of the service's first log group, by
     * context name, so one node of the service does at a time; every node of a service without a log group does.
     */
    fun reportsParked(): Boolean {
        val designated = node.contexts.toSortedMap().values.firstNotNullOfOrNull { it.logGroups.firstOrNull() }
            ?: return true
        return DESIGNATED_PARTITION in designated.subscriptions.partitions
    }

    /** Sets the gauge [metric] of [tags] to [value], registering it the first time; its key. */
    private fun gauge(metric: MetricSpec, tags: Tags, value: Double): Pair<String, Tags> {
        val key = metric.name to tags
        gauges.computeIfAbsent(key) { _ ->
            val holder = AtomicReference(value)
            holder to metric.gauge(meters, holder, tags) { it.get() }
        }.first.set(value)
        return key
    }

    private fun subscriptionTags(group: String, subscription: String): Tags =
        WowMetrics.PAUSED_PARTITIONS.tagsOf(group, subscription)

    /** Stops sampling, when the sampler is its own. */
    override fun close() {
        if (ownsSampler) {
            sampler.close()
        }
    }

    @InternalWowApi
    companion object {
        /** The partition of the service's first log group whose owner reports the parked entries. */
        private const val DESIGNATED_PARTITION: Int = 0

        /** The share of a log of [head] entries a subscription at [watermark] has handled: 1 for an empty log. */
        fun progress(watermark: Long, head: Long): Double =
            if (head <= 0) 1.0 else (watermark.toDouble() / head).coerceIn(0.0, 1.0)
    }
}
