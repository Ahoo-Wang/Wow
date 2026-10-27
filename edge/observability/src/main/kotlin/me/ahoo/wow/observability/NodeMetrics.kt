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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.node.ParkedKey
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.pauses
import me.ahoo.wow.runtime.node.throttled
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The gauges of a [node] that are read, not heard, sampled every [interval] into [meters]:
 *
 * - [WowMetrics.PARKED_AGGREGATES]: how many entries each subscription has parked, as the node's ledgers list them
 *   ([ServiceNode.parked]), counted up to [maxParked] per subscription. The ledgers are the service's, shared by its
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
 * A sample that fails is logged; the next one is taken all the same. [start] starts sampling, [close] stops it.
 */
@InternalWowApi
class NodeMetrics(
    private val node: ServiceNode,
    private val meters: MeterRegistry,
    private val interval: Duration = DEFAULT_INTERVAL,
    private val maxParked: Int = DEFAULT_MAX_PARKED,
    context: CoroutineContext = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + context)
    private val gauges = ConcurrentHashMap<Pair<String, Tags>, Pair<AtomicReference<Double>, Gauge>>()

    @Volatile
    private var sampling: Job? = null

    init {
        require(interval.isPositive()) { "The sampling interval is positive." }
        require(maxParked >= 1) { "At least one parked entry is counted." }
    }

    /**
     * Starts sampling every [interval], the first sample one interval from now, once the node has started. Starting
     * twice does nothing.
     */
    @Synchronized
    fun start() {
        if (sampling != null) {
            return
        }
        sampling = scope.launch {
            while (true) {
                delay(interval)
                sampleQuietly()
            }
        }
    }

    /** Takes one sample of every gauge, and removes the gauges it no longer takes. */
    suspend fun sample() {
        val taken = HashSet<Pair<String, Tags>>()
        if (reportsParked()) {
            node.parked.subscriptions.forEach { subscription ->
                taken += gauge(
                    WowMetrics.PARKED_AGGREGATES,
                    Tags.of(WowMetrics.SUBSCRIPTION, subscription),
                    countParked(subscription).toDouble()
                )
            }
        }
        node.contexts.forEach { (name, context) ->
            val tags = Tags.of(WowMetrics.CONTEXT, name)
            taken += gauge(WowMetrics.EXECUTOR_IN_FLIGHT, tags, context.executor.commandsInFlight.toDouble())
            taken += gauge(WowMetrics.EXECUTOR_LANES, tags, context.executor.activeLanes.toDouble())
            taken += gauge(WowMetrics.APPEND_QUEUE_DEPTH, tags, context.log.queued.toDouble())
        }
        val paused = node.pauses.paused().groupingBy { subscriptionTags(it.group, it.subscription) }.eachCount()
        val throttled = node.throttled.groupingBy { subscriptionTags(it.group, it.subscription) }.eachCount()
        val subscriptions = HashSet<Tags>(paused.keys + throttled.keys)
        node.contexts.values.flatMap { it.logGroups }.forEach { group ->
            val runtime = group.subscriptions
            val heads = HashMap<Int, Long>()
            runtime.positions().forEach { position ->
                val head = heads.getOrPut(position.partition) { group.log.head(position.partition) ?: 0L }
                val tags = Tags.of(
                    WowMetrics.GROUP,
                    runtime.group,
                    WowMetrics.SUBSCRIPTION,
                    position.subscription,
                    WowMetrics.PARTITION,
                    position.partition.toString(),
                )
                taken += gauge(WowMetrics.POSITION, tags, position.watermark.toDouble())
                taken += gauge(WowMetrics.BACKLOG, tags, (head - position.watermark).coerceAtLeast(0).toDouble())
                taken += gauge(WowMetrics.PROGRESS, tags, progress(position.watermark, head))
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

    /** How many entries [subscription] has parked, up to [maxParked]. */
    private suspend fun countParked(subscription: String): Int {
        var count = 0
        var after: ParkedKey? = null
        do {
            val page = node.parked.list(subscription, after, minOf(ParkedOps.MAX_LIMIT, maxParked - count))
            count += page.entries.size
            after = page.next
        } while (after != null && count < maxParked)
        return count
    }

    @Suppress("TooGenericExceptionCaught") // A failed sample is logged; the next one is taken all the same.
    private suspend fun sampleQuietly() {
        try {
            sample()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to sample the metrics of node ${node.node}; sampling again in $interval." }
        }
    }

    /** Sets the gauge [name] of [tags] to [value], registering it the first time; its key. */
    private fun gauge(name: String, tags: Tags, value: Double): Pair<String, Tags> {
        val key = name to tags
        gauges.computeIfAbsent(key) { _ ->
            val holder = AtomicReference(value)
            holder to Gauge.builder(name, holder) { it.get() }.tags(tags).register(meters)
        }.first.set(value)
        return key
    }

    private fun subscriptionTags(group: String, subscription: String): Tags =
        Tags.of(WowMetrics.GROUP, group, WowMetrics.SUBSCRIPTION, subscription)

    /** Stops sampling. */
    override fun close() {
        scope.cancel()
    }

    @InternalWowApi
    companion object {
        val DEFAULT_INTERVAL: Duration = 15.seconds
        const val DEFAULT_MAX_PARKED: Int = 10_000

        /** The partition of the service's first log group whose owner reports the parked entries. */
        private const val DESIGNATED_PARTITION: Int = 0
        private val logger = KotlinLogging.logger {}

        /** The share of a log of [head] entries a subscription at [watermark] has handled: 1 for an empty log. */
        fun progress(watermark: Long, head: Long): Double =
            if (head <= 0) 1.0 else (watermark.toDouble() / head).coerceIn(0.0, 1.0)
    }
}
