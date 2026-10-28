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

import me.ahoo.wow.spi.observation.ObservedEvent
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
 * store reads by reason (`gap`, `overflow`, `idle`, `start`), the refused commands by code (a busy
 * lane or node is `wow.command.busy`), the timed-out waits by the stage they had reached, the dropped wait signals,
 * the failed forwards and the partitions paused and resumed on a systemic failure. Only with
 * `bench.signals.enabled=true`, which every V10 run of the CI benchmark sets; otherwise the node has no observer. The
 * counts are cumulative from the start. The eShop soak's nodes run a copy (SoakSignals in `:eshop-e2e`'s soakTest
 * source set); BenchSignalsCopyTest keeps the two in line.
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

    override fun observed(event: ObservedEvent) {
        when (event) {
            is ObservedEvent.TailRead -> count("tailRead", event.reason.value)
            is ObservedEvent.Refused -> count("refused", event.code)
            is ObservedEvent.WaitTimedOut -> count("waitTimedOut", event.stage.toString())
            is ObservedEvent.SignalDropped -> count("signalDropped", event.reason.value)
            is ObservedEvent.ForwardFailed -> count("forwardFailed", event.outcome.value)
            // The reason names the failing aggregate and its exception, so the key is the subscription alone.
            is ObservedEvent.PartitionPaused -> count("partitionPaused", event.subscription)
            is ObservedEvent.PartitionResumed -> count("partitionResumed", event.subscription)
            else -> Unit
        }
    }

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
