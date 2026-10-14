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

package me.ahoo.wow.opentelemetry

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The gauges of a [node] that are read, not heard (decision D174), sampled every [interval] into [meters]:
 *
 * - [WowMetrics.PARKED]: how many entries each subscription has parked, as the node's ledgers list them
 *   ([ServiceNode.parked]), counted up to [maxParked] per subscription.
 * - [WowMetrics.POSITION], [WowMetrics.BACKLOG] and [WowMetrics.PROGRESS]: for each subscription of each log group,
 *   in each partition it runs on this node ([me.ahoo.wow.runtime.subscription.SubscriptionRuntime.positions]), its
 *   watermark, the entries of the log after it, and the watermark over the log's head: how far a rebuild (a
 *   subscription under a new name, from the beginning) has come.
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
    private val gauges = ConcurrentHashMap<Pair<String, Tags>, AtomicReference<Double>>()

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

    /** Takes one sample of every gauge. */
    suspend fun sample() {
        node.parked.subscriptions.forEach { subscription ->
            gauge(
                WowMetrics.PARKED,
                Tags.of(WowMetrics.SUBSCRIPTION, subscription),
                countParked(subscription).toDouble()
            )
        }
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
                gauge(WowMetrics.POSITION, tags, position.watermark.toDouble())
                gauge(WowMetrics.BACKLOG, tags, (head - position.watermark).coerceAtLeast(0).toDouble())
                gauge(WowMetrics.PROGRESS, tags, progress(position.watermark, head))
            }
        }
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

    private fun gauge(name: String, tags: Tags, value: Double) {
        gauges.computeIfAbsent(name to tags) { _ ->
            AtomicReference(value).also { holder ->
                Gauge.builder(name, holder) { it.get() }.tags(tags).register(meters)
            }
        }.set(value)
    }

    /** Stops sampling. */
    override fun close() {
        scope.cancel()
    }

    @InternalWowApi
    companion object {
        val DEFAULT_INTERVAL: Duration = 15.seconds
        const val DEFAULT_MAX_PARKED: Int = 10_000
        private val logger = KotlinLogging.logger {}

        /** The share of a log of [head] entries a subscription at [watermark] has handled: 1 for an empty log. */
        fun progress(watermark: Long, head: Long): Double =
            if (head <= 0) 1.0 else (watermark.toDouble() / head).coerceIn(0.0, 1.0)
    }
}
