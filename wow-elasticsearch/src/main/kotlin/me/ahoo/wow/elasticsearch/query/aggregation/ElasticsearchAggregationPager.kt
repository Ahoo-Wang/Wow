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

package me.ahoo.wow.elasticsearch.query.aggregation

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchPointInTime
import me.ahoo.wow.elasticsearch.query.requireComplete
import me.ahoo.wow.query.GroupWindow
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import kotlin.math.min

internal const val ROOT_AGGREGATION = "__wow_aggregation"
internal const val GROUP_AGGREGATION = "__wow_groups"

/**
 * A bucket_script pipeline requires a multi-bucket parent aggregation. Grouped queries nest the
 * metrics under the composite [GROUP_AGGREGATION], but a summary (ungrouped) query would otherwise
 * leave them under single-bucket scopes (`filter`/`nested`), which Elasticsearch rejects with
 * "Expected a multi bucket aggregation". One catch-all `filters` bucket provides the required
 * multi-bucket parent without changing any computed value.
 */
internal const val SUMMARY_BUCKET_AGGREGATION = "__wow_summary_bucket"
internal const val SUMMARY_BUCKET_KEY = "_wow"

internal class ElasticsearchAggregationPager(
    private val client: ReactiveElasticsearchClient,
    private val indexName: String,
    private val batchSize: Int = DEFAULT_SEARCH_BATCH_SIZE,
    keepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE,
) {
    private val pointInTime = ElasticsearchPointInTime(client, indexName, keepAlive)

    init {
        require(batchSize in 1..DEFAULT_SEARCH_BATCH_SIZE) {
            "batchSize must be between 1 and $DEFAULT_SEARCH_BATCH_SIZE."
        }
        require(keepAlive.toMillis() > 0) { "keepAlive must be greater than or equal to 1ms." }
    }

    /**
     * Streams the plan's groups in composite order: every group for [GroupWindow.All], otherwise pages sized to stop
     * at the window's limit. An ungrouped plan answers with its one summary row. A window whose limit fits one page
     * is one search, so it needs no point in time: there is no second page for the index to change under.
     */
    fun execute(plan: ElasticsearchAggregationPlan, window: GroupWindow): Flux<ObjectNode> = Flux.defer {
        val limit = (window as? GroupWindow.First)?.limit
        if (plan.groupSources.isEmpty()) {
            search(plan, null, plan.aggregation(emptyMap(), 0)).map { response -> response.summary(plan) }.flux()
        } else {
            // Built before the point in time opens, so a plan that cannot be expressed fails without one.
            val firstAggregation = plan.aggregation(emptyMap(), plan.pageSize(limit, 0))
            if (limit != null && limit <= plan.pageSize(null, 0)) {
                return@defer searchPage(plan, null, limit, aggregation = firstAggregation)
                    .flatMapIterable { it.rows }
            }
            pointInTime.use { pit ->
                searchPage(plan, pit, limit, aggregation = firstAggregation)
                    .expand { page ->
                        if (page.shouldStop(limit)) {
                            Mono.empty()
                        } else {
                            searchPage(plan, pit, limit, page.afterKey, page.fetched)
                        }
                    }
                    .concatMapIterable({ it.rows }, 1)
            }
        }
    }

    private fun searchPage(
        plan: ElasticsearchAggregationPlan,
        pit: ElasticsearchPointInTime.Session?,
        limit: Int?,
        afterKey: Map<String, FieldValue> = emptyMap(),
        fetched: Int = 0,
        aggregation: Aggregation = plan.aggregation(afterKey, plan.pageSize(limit, fetched)),
    ): Mono<AggregationPage> {
        return search(plan, pit, aggregation).map { response ->
            val composite = response.innermost(plan).getValue(GROUP_AGGREGATION).composite()
            val rows = composite.buckets().array().map { it.toRow(plan) }
            AggregationPage(rows, composite.afterKey(), fetched + rows.size)
        }
    }

    private fun ElasticsearchAggregationPlan.pageSize(limit: Int?, fetched: Int): Int {
        val bucketWidth = 1 + metrics.sumOf { it.subBuckets } + metrics.count { it.filter != null }
        val pageCapacity = (batchSize / bucketWidth).coerceAtLeast(1)
        return if (limit == null) pageCapacity else min(pageCapacity, limit - fetched)
    }

    /** The buckets one metric adds per group: a terms or top-hits sub-aggregation adds one. */
    private val ElasticsearchAggregationMetric.subBuckets: Int
        get() = when (this) {
            is ElasticsearchAggregationMetric.Any, is ElasticsearchAggregationMetric.Edge -> 1
            is ElasticsearchAggregationMetric.Count, is ElasticsearchAggregationMetric.Numeric,
            is ElasticsearchAggregationMetric.DistinctCount, is ElasticsearchAggregationMetric.Percentile,
            is ElasticsearchAggregationMetric.Derived,
            -> 0
        }

    private fun search(
        plan: ElasticsearchAggregationPlan,
        pit: ElasticsearchPointInTime.Session?,
        aggregation: Aggregation,
    ): Mono<ResponseBody<Map<*, *>>> = Mono.defer {
        val request = SearchRequest.of {
            it.query(plan.rootQuery)
                .size(0)
                .allowPartialSearchResults(false)
                .trackTotalHits { track -> track.enabled(false) }
                .runtimeMappings(plan.runtimeMappings)
                .aggregations(ROOT_AGGREGATION, aggregation)
                .apply {
                    if (pit == null) {
                        index(indexName)
                    } else {
                        pit { pointInTime ->
                            pointInTime.id(pit.id)
                                .keepAlive { keepAlive ->
                                    keepAlive.time(
                                        this@ElasticsearchAggregationPager.pointInTime.keepAliveValue,
                                    )
                                }
                        }
                    }
                }
        }
        client.search(request, Map::class.java)
    }.doOnNext { pit?.update(it.pitId()) }
        .map { it.requireComplete() }

    private fun ElasticsearchAggregationPlan.aggregation(
        afterKey: Map<String, FieldValue>,
        pageSize: Int,
    ): Aggregation {
        var aggregations = if (groupSources.isEmpty()) {
            summaryMetricAggregations()
        } else {
            mapOf(GROUP_AGGREGATION to groupAggregation(afterKey, pageSize))
        }
        elements.indices.reversed().forEach { index ->
            val element = elements[index]
            val filter = Aggregation.of { builder -> builder.filter(element.filter).aggregations(aggregations) }
            val nested = Aggregation.of { builder ->
                builder.nested { it.path(element.path) }
                    .aggregations(filterAggregationName(index), filter)
            }
            aggregations = mapOf(nestedAggregationName(index) to nested)
        }
        if (elements.isNotEmpty()) return aggregations.values.single()
        if (groupSources.isNotEmpty()) return aggregations.getValue(GROUP_AGGREGATION)
        return Aggregation.of { builder ->
            builder.filter { it.matchAll { matchAll -> matchAll } }.aggregations(aggregations)
        }
    }

    private fun ElasticsearchAggregationPlan.groupAggregation(
        afterKey: Map<String, FieldValue>,
        pageSize: Int,
    ): Aggregation = Aggregation.of { builder ->
        builder.composite { composite ->
            composite.sources(groupSources).size(pageSize).apply {
                if (afterKey.isNotEmpty()) after(afterKey)
            }
        }.aggregations(metricAggregations())
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One exhaustive branch per metric type.
    private fun ElasticsearchAggregationPlan.metricAggregations(): Map<String, Aggregation> = buildMap {
        metrics.forEach { metric ->
            when (metric) {
                is ElasticsearchAggregationMetric.Count -> metric.filter?.let { filter ->
                    put(metric.alias, Aggregation.of { builder -> builder.filter(filter) })
                }

                is ElasticsearchAggregationMetric.Any -> putMetricAggregations(
                    metric,
                    metric.alias to Aggregation.of { builder ->
                        builder.terms { terms -> terms.field(metric.field).size(1) }
                    },
                )

                is ElasticsearchAggregationMetric.Numeric -> putMetricAggregations(
                    metric,
                    metric.alias to Aggregation.of { builder ->
                        when (metric.function) {
                            AggregationFunction.SUM -> builder.sum { it.field(metric.field) }
                            AggregationFunction.AVG -> builder.avg { it.field(metric.field) }
                            AggregationFunction.MIN -> builder.min { it.field(metric.field) }
                            AggregationFunction.MAX -> builder.max { it.field(metric.field) }
                            AggregationFunction.STDDEV, AggregationFunction.VARIANCE ->
                                builder.extendedStats { it.field(metric.field) }
                        }
                    },
                    metric.valueCountAlias to Aggregation.of { builder ->
                        builder.valueCount { it.field(metric.field) }
                    },
                )

                is ElasticsearchAggregationMetric.Edge -> putMetricAggregations(metric, metric.alias to metric.topHit())

                is ElasticsearchAggregationMetric.DistinctCount -> putMetricAggregations(
                    metric,
                    metric.alias to Aggregation.of { builder ->
                        builder.cardinality { it.field(metric.field) }
                    },
                )

                is ElasticsearchAggregationMetric.Percentile -> putMetricAggregations(
                    metric,
                    metric.alias to Aggregation.of { builder ->
                        builder.percentiles {
                            it.field(metric.field)
                                .percents(listOf(metric.percentile))
                                .keyed(false)
                        }
                    },
                    metric.valueCountAlias to Aggregation.of { builder ->
                        builder.valueCount { it.field(metric.field) }
                    },
                )

                is ElasticsearchAggregationMetric.Derived -> put(
                    metric.alias,
                    Aggregation.of { builder ->
                        builder.bucketScript { bucketScript ->
                            bucketScript.bucketsPath { it.dict(metric.bucketsPath) }
                                .script(metric.script)
                        }
                    },
                )
            }
        }
    }

    /**
     * Wraps summary (ungrouped) metric aggregations in one catch-all [SUMMARY_BUCKET_AGGREGATION]
     * bucket whenever a derived metric requires a multi-bucket bucket_script parent.
     */
    private fun ElasticsearchAggregationPlan.summaryMetricAggregations(): Map<String, Aggregation> {
        val aggregations = metricAggregations()
        if (metrics.none { it is ElasticsearchAggregationMetric.Derived }) {
            return aggregations
        }
        return mapOf(
            SUMMARY_BUCKET_AGGREGATION to Aggregation.of { builder ->
                builder.filters { filters ->
                    filters.filters { buckets ->
                        buckets.keyed(
                            mapOf(SUMMARY_BUCKET_KEY to Query.of { it.matchAll { matchAll -> matchAll } }),
                        )
                    }
                }.aggregations(aggregations)
            },
        )
    }

    private class AggregationPage(
        val rows: List<ObjectNode>,
        val afterKey: Map<String, FieldValue>,
        val fetched: Int,
    ) {
        fun shouldStop(limit: Int?): Boolean =
            afterKey.isEmpty() || rows.isEmpty() || (limit != null && fetched >= limit)
    }
}

internal fun nestedAggregationName(index: Int): String = "__wow_element_$index"

internal fun filterAggregationName(index: Int): String = "__wow_element_filter_$index"

internal fun metricFilterAggregationName(alias: String): String = "__wow_metric_filter_$alias"

/**
 * Publishes a metric's aggregations directly, or nested under one filter aggregation
 * so only records accepted by the metric filter contribute to it.
 */
private fun MutableMap<String, Aggregation>.putMetricAggregations(
    metric: ElasticsearchAggregationMetric,
    vararg subAggregations: Pair<String, Aggregation>,
) {
    val filter = metric.filter
    if (filter == null) {
        subAggregations.forEach { (name, aggregation) -> put(name, aggregation) }
        return
    }
    put(
        metricFilterAggregationName(metric.alias),
        Aggregation.of { builder ->
            builder.filter(filter)
            subAggregations.forEach { (name, aggregation) -> builder.aggregations(name, aggregation) }
            builder
        },
    )
}

/** The single earliest (FIRST) or latest (LAST) hit by `orderBy`, carrying only the value's doc value. */
private fun ElasticsearchAggregationMetric.Edge.topHit(): Aggregation = Aggregation.of { builder ->
    builder.topHits { top ->
        top.size(1)
            .source { it.fetch(false) }
            .sort { sort -> sort.field { it.field(orderBy).order(if (last) SortOrder.Desc else SortOrder.Asc) } }
            .docvalueFields { docValue ->
                docValue.field(field).apply { if (epochMillis) format("epoch_millis") }
            }
    }
}
