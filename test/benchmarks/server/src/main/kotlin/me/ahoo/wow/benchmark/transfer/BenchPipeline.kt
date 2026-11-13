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

package me.ahoo.wow.benchmark.transfer

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.log.HandoffLog
import me.ahoo.wow.runtime.node.ServiceNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The saga pipeline's depths and the append queue's calls, logged once a second as `bench-pipeline {json}` at WARN,
 * only with `bench.pipeline.enabled=true` (off by default, so every other run measures a node that counts none of
 * it): where a command's chain queues between one commit and the next, which the executor's in-flight count does not
 * see. One line a second for the node, the shape of `bench-signals`' and `bench-query-limits`':
 * `{"t": <seconds since start>, "<section>": {"<field>": <number>, …}, …}`, every value a number, so a reader takes a
 * section it knows and passes over one it does not. A section is left out while the node has nothing for it.
 *
 * - `appendDepth`, now, summed over the contexts: `queued`, the appends not taken into a batch yet (the
 *   `wow.log.append_queue.depth` gauge's read); `handedGroups` and `handedStreams`, the lanes' groups handed to the
 *   committers and not yet taken into a call.
 * - `appendCalls`, cumulative from this line's first read: `calls` to the log, the `streams` they carried, and the calls
 *   `full` at their cap (`mergeMax` merged). Streams over calls between two lines is the mean call of that second.
 * - `handoff`, cumulative from the node's start: the entries the tails `delivered` from memory and their `storeReads`.
 * - `window` and `windowMax`, now, per subscription: the entries its workers took above their watermarks, summed over
 *   the partitions and the largest one partition's, against its in-flight bound (`wow.subscription.max-in-flight`).
 *
 * Every read is of an atomic or volatile field, with no lock taken on the path a command or an entry runs. The
 * append queue counts its calls and handed groups only once read (`PartitionAppendQueue.counts`): an atomic add per
 * call and per group, none per append. The hand-off's channels have no depth to read without a count on every entry,
 * and the throttle's waiters are behind its monitor, so neither is here; the throttled partitions are the
 * `partitionThrottled` less `partitionUnthrottled` of `bench-signals`.
 *
 * The node's queues are Wow's internal API, which an application does not use (the architecture's internal opt-in
 * rule): this class is the benchmark server's one exception, a reader of the node it measures that calls nothing on
 * its write path. No other class of the server names those types, so a change to them breaks this file alone.
 */
@OptIn(InternalWowApi::class)
class BenchPipeline(private val sections: () -> Map<String, Map<String, Number>>?) : AutoCloseable {
    private val started = System.currentTimeMillis()
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "bench-pipeline").apply { isDaemon = true }
    }

    fun start() {
        ticker.scheduleAtFixedRate({ snapshot()?.let { log.warn("bench-pipeline {}", it) } }, 1, 1, TimeUnit.SECONDS)
    }

    override fun close() {
        ticker.shutdownNow()
    }

    /** The sections as one JSON object, in their order; null when there are none. */
    fun snapshot(): String? {
        val read = sections()?.filterValues { it.isNotEmpty() }
        if (read.isNullOrEmpty()) return null
        return buildString {
            append("{\"t\":").append((System.currentTimeMillis() - started) / MILLIS_PER_SECOND)
            read.forEach { (section, fields) ->
                append(",\"").append(section).append("\":{")
                append(fields.entries.joinToString(",") { "\"${it.key}\":${it.value}" })
                append('}')
            }
            append('}')
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BenchPipeline::class.java)
        private const val MILLIS_PER_SECOND = 1_000

        /** The line of the node among [beans]. */
        fun of(beans: BeanFactory): BenchPipeline {
            val node = beans.getBean(ServiceNode::class.java)
            return BenchPipeline { sectionsOf(node) }
        }

        /** The sections of [node] now: its contexts' append queues, their log groups' hand-off logs and positions. */
        private fun sectionsOf(node: ServiceNode): Map<String, Map<String, Number>> {
            val queues = node.contexts.values.map { it.log }
            val counts = queues.map { it.counts() }
            val groups = node.contexts.values.flatMap { it.logGroups }
            // A group's log is its hand-off log (ContextAssembly hands each group's commits to its tails).
            val handoffs = groups.mapNotNull { it.log as? HandoffLog }
            val positions = groups.flatMap { it.subscriptions.positions() }.groupBy { it.subscription }.toSortedMap()
            return linkedMapOf(
                "appendDepth" to linkedMapOf(
                    "queued" to queues.sumOf { it.queued },
                    "handedGroups" to counts.sumOf { it.handedGroups },
                    "handedStreams" to counts.sumOf { it.handedStreams },
                ),
                "appendCalls" to linkedMapOf(
                    "calls" to counts.sumOf { it.calls },
                    "streams" to counts.sumOf { it.streams },
                    "full" to counts.sumOf { it.full },
                ),
                "handoff" to if (handoffs.isEmpty()) {
                    emptyMap()
                } else {
                    linkedMapOf(
                        "delivered" to handoffs.sumOf { it.delivered },
                        "storeReads" to handoffs.sumOf { it.storeReads },
                    )
                },
                "window" to positions.mapValues { (_, each) -> each.sumOf { it.inFlight } },
                "windowMax" to positions.mapValues { (_, each) -> each.maxOf { it.inFlight } },
            )
        }
    }
}

/** [BenchPipeline] on the node, with `bench.pipeline.enabled=true` only. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bench.pipeline", name = ["enabled"], havingValue = "true")
class BenchPipelineConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun benchPipeline(beans: BeanFactory): BenchPipeline = BenchPipeline.of(beans)
}
