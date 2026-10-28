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

@file:OptIn(InternalWowApi::class)

package me.ahoo.wow.benchmark.transfer

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.admission.AdaptiveQueryLimit
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.store.QueryStores
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The query bounds of the node's stores, logged every second as `bench-query-limits {json}`: each store's bound now
 * and its ceiling, the queries running and queued, and, for a bound that adapts to the store's write pressure, whether
 * the last period pressed, how often the bound was lowered, and the pressure it read. So a run's `wow.query.busy`
 * answers can be read against the bound that refused them: a bulkhead at its ceiling, or one the adaptation lowered.
 * The meters (`wow.query.limit.current`, `wow.query.pressure`, `wow.query.limit.decreases`) say the same, but the
 * server runs without wow-observability, whose cost the measurements would carry.
 */
class BenchQueryLimits(private val stores: QueryStores) : AutoCloseable {
    private val started = System.currentTimeMillis()
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "bench-query-limits").apply { isDaemon = true }
    }

    fun start() {
        if (stores.bulkheads.isEmpty()) return
        ticker.scheduleAtFixedRate({ log.warn("bench-query-limits {}", snapshot()) }, 1, 1, TimeUnit.SECONDS)
    }

    override fun close() {
        ticker.shutdownNow()
    }

    /** `{"t": <seconds since start>, "<store>": {"limit": …, "ceiling": …, "running": …, "queued": …, …}, …}`. */
    fun snapshot(): String = buildString {
        append("{\"t\":").append((System.currentTimeMillis() - started) / MILLIS_PER_SECOND)
        val adaptive = stores.limits.associateBy { it.bulkhead.store }
        stores.bulkheads.toSortedMap().forEach { (store, bulkhead) ->
            append(",\"").append(store).append("\":")
            append(of(bulkhead, adaptive[store]))
        }
        append('}')
    }

    private companion object {
        private val log = LoggerFactory.getLogger(BenchQueryLimits::class.java)
        private const val MILLIS_PER_SECOND = 1_000

        fun of(bulkhead: QueryBulkhead, limit: AdaptiveQueryLimit?): String = buildString {
            append("{\"limit\":").append(bulkhead.limit)
            append(",\"ceiling\":").append(bulkhead.ceiling)
            append(",\"running\":").append(bulkhead.running)
            append(",\"queued\":").append(bulkhead.queued)
            if (limit != null) {
                val sample = limit.lastSample
                append(",\"floor\":").append(limit.floor)
                append(",\"pressed\":").append(limit.pressed)
                append(",\"decreases\":").append(limit.decreases)
                append(",\"appends\":").append(sample.appends)
                sample.appendP90?.let { append(",\"appendP90Ms\":").append(it.inWholeMicroseconds / 1_000.0) }
                sample.baseline?.let { append(",\"baselineMs\":").append(it.inWholeMicroseconds / 1_000.0) }
                append(",\"waitingForConnection\":").append(sample.waitingForConnection)
            }
            append('}')
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bench.signals", name = ["enabled"], havingValue = "true")
class BenchQueryLimitsConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun benchQueryLimits(stores: QueryStores): BenchQueryLimits = BenchQueryLimits(stores)
}
