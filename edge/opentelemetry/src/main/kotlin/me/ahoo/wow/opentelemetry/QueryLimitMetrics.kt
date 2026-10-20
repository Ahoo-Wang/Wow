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

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.AdaptiveQueryLimit
import me.ahoo.wow.query.QueryBulkhead

/**
 * The query bulkheads' meters (decision D252), read live: [WowMetrics.QUERY_LIMIT] of every bulkhead, and, of those
 * whose limit adapts, [WowMetrics.QUERY_PRESSURE] and [WowMetrics.QUERY_LIMIT_DECREASES]; each tagged with its store.
 */
@InternalWowApi
object QueryLimitMetrics {
    /** Registers the meters of [bulkheads] and of their adaptive [limits] in [meters]. */
    fun register(bulkheads: Collection<QueryBulkhead>, limits: Collection<AdaptiveQueryLimit>, meters: MeterRegistry) {
        bulkheads.forEach { bulkhead ->
            Gauge.builder(WowMetrics.QUERY_LIMIT, bulkhead) { it.limit.toDouble() }
                .tags(tagsOf(bulkhead))
                .register(meters)
        }
        limits.forEach { limit ->
            val tags = tagsOf(limit.bulkhead)
            Gauge.builder(WowMetrics.QUERY_PRESSURE, limit) { if (it.pressed) 1.0 else 0.0 }.tags(tags).register(meters)
            FunctionCounter.builder(WowMetrics.QUERY_LIMIT_DECREASES, limit) { it.decreases.toDouble() }
                .tags(tags)
                .register(meters)
        }
    }

    private fun tagsOf(bulkhead: QueryBulkhead): Tags = Tags.of(WowMetrics.STORE, bulkhead.store)
}
