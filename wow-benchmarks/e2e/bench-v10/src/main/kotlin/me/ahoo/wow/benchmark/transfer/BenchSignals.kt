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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.spi.observation.Observer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

/**
 * The node's overload and fallback signals, counted and logged once a second (`bench-signals {json}` at WARN, the
 * level the server logs at), so a benchmark can tell a cliff from plain overload without a metrics stack: the tails'
 * store reads by reason (`gap`, `overflow`, `idle`, `start`; decision D249), the refused commands by code (a busy
 * lane or node is `wow.command.busy`), the timed-out waits by the stage they had reached, the dropped wait signals,
 * the failed forwards and the partitions paused and resumed on a systemic failure (decision D243). Only with `bench.signals.enabled=true` (the CI benchmark's cliff
 * suites); otherwise the node has no observer, as in every other run. The counts are cumulative from the start.
 */
class BenchSignals : Observer, AutoCloseable {
    private val counters = ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>>()
    private val started = System.currentTimeMillis()
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "bench-signals").apply { isDaemon = true }
    }

    fun start() {
        ticker.scheduleAtFixedRate({ log.warn("bench-signals {}", snapshot()) }, 1, 1, TimeUnit.SECONDS)
    }

    override fun close() {
        ticker.shutdownNow()
        log.warn("bench-signals {}", snapshot())
    }

    private fun count(kind: String, key: String) =
        counters.computeIfAbsent(kind) { ConcurrentHashMap() }.computeIfAbsent(key) { LongAdder() }.increment()

    /** The counts as one JSON object: `{"t": <seconds since start>, "<kind>": {"<key>": <count>, …}, …}`. */
    fun snapshot(): String = buildString {
        append("{\"t\":").append((System.currentTimeMillis() - started) / MILLIS_PER_SECOND)
        counters.toSortedMap().forEach { (kind, keys) ->
            append(",\"").append(kind).append("\":{")
            append(keys.toSortedMap().entries.joinToString(",") { "\"${it.key}\":${it.value.sum()}" })
            append('}')
        }
        append('}')
    }

    override fun tailRead(context: String, partition: Int, reason: String) = count("tailRead", reason)

    override fun refused(type: AggregateType, code: String) = count("refused", code)

    override fun waitTimedOut(type: AggregateType, stage: Stage) = count("waitTimedOut", stage.toString())

    override fun signalDropped(origin: String, reason: String) = count("signalDropped", reason)

    override fun forwardFailed(type: AggregateType, outcome: String) = count("forwardFailed", outcome)

    // The reason names the failing aggregate and its exception, so the key is the subscription alone.
    override fun partitionPaused(group: String, subscription: String, partition: Int, reason: String) =
        count("partitionPaused", subscription)

    override fun partitionResumed(group: String, subscription: String, partition: Int, pausedMillis: Long) =
        count("partitionResumed", subscription)

    private companion object {
        private val log = LoggerFactory.getLogger(BenchSignals::class.java)
        private const val MILLIS_PER_SECOND = 1_000
    }
}

/** [BenchSignals] as the node's observer, with `bench.signals.enabled=true` only. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bench.signals", name = ["enabled"], havingValue = "true")
class BenchSignalsConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun benchSignals(): BenchSignals = BenchSignals()
}
