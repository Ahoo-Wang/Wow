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

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.CompositeHealthContributor
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.registry.HealthContributorRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The query bounds of the node's stores, logged every second as `bench-query-limits {json}`, as the node's health
 * says them (the `wow` contributor's `queries` component, which `/actuator/health/wow` shows an operator): per store,
 * its bound now (`limit`) and its `ceiling`, the queries `running` and `queued`, and, for a bound that adapts to the
 * store's write pressure, whether the last period pressed (`pressure`), how often it was lowered (`decreases`), and
 * the pressure it read. So a run's `wow.query.busy` answers read against the bound that refused them: a bulkhead at
 * its ceiling, or one the adaptation lowered. The server runs without wow-observability, whose meters say the same
 * at a cost the measurements would carry.
 */
class BenchQueryLimits(private val queries: () -> Map<String, Any?>?) : AutoCloseable {
    private val started = System.currentTimeMillis()
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "bench-query-limits").apply { isDaemon = true }
    }

    fun start() {
        ticker.scheduleAtFixedRate(
            { snapshot()?.let { log.warn("bench-query-limits {}", it) } },
            1,
            1,
            TimeUnit.SECONDS
        )
    }

    override fun close() {
        ticker.shutdownNow()
    }

    /** `{"t": <seconds since start>, "<store>": {"limit": …, "ceiling": …, …}, …}`; null when the node has no stores. */
    fun snapshot(): String? {
        val stores = queries()?.toSortedMap()?.filterValues { it is Map<*, *> }
        if (stores.isNullOrEmpty()) return null
        return buildString {
            append("{\"t\":").append((System.currentTimeMillis() - started) / MILLIS_PER_SECOND)
            stores.forEach { (store, details) ->
                append(",\"").append(store).append("\":{")
                append(
                    (details as Map<*, *>).entries.filter { it.value is Number || it.value is Boolean }
                        .joinToString(",") { "\"${it.key}\":${it.value}" },
                )
                append('}')
            }
            append('}')
        }
    }

    companion object {
        /** The node's health contributor and its component of the stores' query bounds. */
        const val WOW: String = "wow"
        const val QUERIES: String = "queries"

        private val log = LoggerFactory.getLogger(BenchQueryLimits::class.java)
        private const val MILLIS_PER_SECOND = 1_000

        /** The details of the `queries` component of [registry]'s `wow` contributor, or null without it. */
        fun of(registry: HealthContributorRegistry): Map<String, Any?>? {
            val wow = registry.getContributor(WOW) as? CompositeHealthContributor ?: return null
            val queries = wow.getContributor(QUERIES) as? HealthIndicator ?: return null
            return queries.health(true)?.details
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bench.signals", name = ["enabled"], havingValue = "true")
class BenchQueryLimitsConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun benchQueryLimits(registry: HealthContributorRegistry): BenchQueryLimits =
        BenchQueryLimits { BenchQueryLimits.of(registry) }
}
