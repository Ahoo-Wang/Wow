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

import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.observability.WowMetrics.tagValue
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.spi.observation.Observation
import java.util.concurrent.TimeUnit

/**
 * Observes every query a gateway runs: a span `query <context>.<aggregate>` with its query type, the
 * child of the span the query is asked in, and its time in the [WowMetrics.QUERY_DURATION] timer of [meters], tagged with how
 * it ended: `ok`, `cached`, `error` or `cancelled`. A query of a dataset is tagged with the dataset ([WowMetrics.DATASET])
 * instead of an aggregate type, and timed in [WowMetrics.DATASET_QUERY_DURATION]. The aggregates a pinned events query had checked count in
 * [WowMetrics.EVENTS_PINNED], by how they were read.
 */
@InternalWowApi
class OtelQueryObserver(openTelemetry: OpenTelemetry, meters: MeterRegistry) : QueryObserver {
    private val tracer = openTelemetry.wowTracer()
    private val propagator = openTelemetry.propagators.textMapPropagator
    private val cached = MeterCache(meters)

    override fun start(aggregateType: AggregateType, queryType: QueryType, model: QueryModel): Observation {
        val dataset = model == QueryModel.DATASET
        val parent = Context.current()
        val span = tracer.spanBuilder("query $aggregateType")
            .setParent(parent)
            .setAttribute(WowAttributes.CONTEXT, aggregateType.context)
            .setAttribute(if (dataset) WowAttributes.DATASET else WowAttributes.AGGREGATE, aggregateType.name)
            .setAttribute(WowAttributes.QUERY_TYPE, queryType.tagValue)
            .startSpan()
        return OtelObservation(span, OtelTraceContext(parent.with(span), propagator)) { outcome, nanos ->
            cached.timer(
                if (dataset) WowMetrics.DATASET_QUERY_DURATION else WowMetrics.QUERY_DURATION,
                aggregateType.context,
                aggregateType.name,
                queryType.tagValue,
                outcome.value,
            ).record(nanos, TimeUnit.NANOSECONDS)
        }
    }

    override fun onEventsPinned(aggregateType: AggregateType, batched: Int, pointReads: Int) {
        pinned(aggregateType, WowMetrics.BATCH_READ, batched)
        pinned(aggregateType, WowMetrics.POINT_READ, pointReads)
    }

    private fun pinned(aggregateType: AggregateType, read: String, count: Int) {
        if (count == 0) return
        cached.counter(
            WowMetrics.EVENTS_PINNED,
            aggregateType.context,
            aggregateType.name,
            read
        ).increment(count.toDouble())
    }
}
