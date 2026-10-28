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

package me.ahoo.wow.example.eshop.e2e.soak

import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

/**
 * A copy of the benchmark's BenchSignals (`test/benchmarks/server/src/main/kotlin/me/ahoo/wow/benchmark/transfer/
 * BenchSignals.kt`) for the soak's nodes: keep in sync; fold into a shared module in 10.x. BenchSignalsCopyTest in
 * `:benchmark-server` checks that both count the same signals and log the same `bench-signals {json}` line.
 *
 * The node's overload and fallback signals, counted and logged once a second at WARN: the tails' store reads by reason
 * (D249), the refused commands by code, the timed-out waits by stage, the dropped wait signals, the failed forwards
 * and the partitions paused and resumed on a systemic failure (D243). The soak puts this class on its nodes' class
 * path (`eshop.extra-classpath`) and starts them with `bench.signals.enabled=true`; EshopSoakTest sums every node's
 * last line into its report.
 */
class SoakSignals : Observer, AutoCloseable {
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
            is ObservedEvent.TailRead -> count("tailRead", event.reason)
            is ObservedEvent.Refused -> count("refused", event.code)
            is ObservedEvent.WaitTimedOut -> count("waitTimedOut", event.stage.toString())
            is ObservedEvent.SignalDropped -> count("signalDropped", event.reason)
            is ObservedEvent.ForwardFailed -> count("forwardFailed", event.outcome)
            // The reason names the failing aggregate and its exception, so the key is the subscription alone.
            is ObservedEvent.PartitionPaused -> count("partitionPaused", event.subscription)
            is ObservedEvent.PartitionResumed -> count("partitionResumed", event.subscription)
            else -> Unit
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(SoakSignals::class.java)
        private const val MILLIS_PER_SECOND = 1_000
    }
}

/** [SoakSignals] as the node's observer, with `bench.signals.enabled=true` only: an auto-configuration of the copy. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "bench.signals", name = ["enabled"], havingValue = "true")
class SoakSignalsAutoConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun benchSignals(): SoakSignals = SoakSignals()
}
