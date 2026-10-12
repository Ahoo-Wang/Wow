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
import me.ahoo.wow.elasticsearch.query.toObjectNode
import me.ahoo.wow.query.GroupWindow
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import kotlin.math.max
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
        if (plan.rootSources.isNotEmpty()) return@defer executeRootGroups(plan, limit)
        if (plan.metricOrder.isNotEmpty()) return@defer executeTopN(plan, checkNotNull(limit))
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

    /**
     * The first [limit] groups of a native top-N ([ElasticsearchAggregationPlan.metricOrder]): one `terms` request
     * ordered by the metric, on an index whose one primary shard makes the order exact. One search, so no point in
     * time.
     */
    private fun executeTopN(plan: ElasticsearchAggregationPlan, limit: Int): Flux<ObjectNode> {
        val group = plan.groupSources.single()
        val terms = group.value().toBucketAggregation(limit, plan.metricAggregations(), plan.metricOrder)
        val aggregation = if (plan.elements.isEmpty()) {
            terms
        } else {
            plan.elementAggregations(mapOf(GROUP_AGGREGATION to terms)).values.single()
        }
        return search(plan, null, aggregation).flatMapIterable { response ->
            response.innermost(plan).getValue(GROUP_AGGREGATION)
                .keyedBuckets(group.name(), limit, complete = false)
                .map { bucket ->
                    linkedMapOf<String, Any?>(group.name() to bucket.key).toObjectNode()
                        .setAll(plan.toRow(bucket.docCount, bucket.aggregations))
                }
        }
    }

    /**
     * Streams the groups of a plan with [ElasticsearchAggregationPlan.rootSources]: pages of an outer composite over
     * the root records, each outer bucket holding all of its element groups. The rows come in sort order, since the
     * root groups lead it.
     */
    private fun executeRootGroups(plan: ElasticsearchAggregationPlan, limit: Int?): Flux<ObjectNode> {
        val capacity = plan.pageSize(null, 0)
        val rows = pointInTime.use { pit ->
            searchRootPage(plan, pit, RootPage(), capacity)
                .expand { page ->
                    if (page.shouldStop(limit)) Mono.empty() else searchRootPage(plan, pit, page, capacity)
                }
                .concatMapIterable({ it.rows }, 1)
        }
        return if (limit == null) rows else rows.take(limit.toLong())
    }

    private fun searchRootPage(
        plan: ElasticsearchAggregationPlan,
        pit: ElasticsearchPointInTime.Session,
        previous: RootPage,
        capacity: Int,
    ): Mono<RootPage> {
        // The first page probes one outer bucket; later pages hold as many as fit at the widest seen.
        val size = if (previous.probed) (capacity / previous.widest.coerceAtLeast(1)).coerceIn(1, capacity) else 1
        val aggregation = plan.rootAggregation(previous.afterKey, size, capacity)
        return search(plan, pit, aggregation).map { response ->
            val composite = response.aggregations().getValue(ROOT_AGGREGATION).composite()
            val buckets = composite.buckets().array()
            val groups = buckets.map { it.rootRows(plan, capacity) }
            val rows = groups.flatten()
            RootPage(
                rows = rows,
                afterKey = composite.afterKey(),
                fetched = previous.fetched + rows.size,
                widest = max(previous.widest, groups.maxOfOrNull { it.size } ?: 0),
                probed = true,
                exhausted = buckets.isEmpty(),
            )
        }
    }

    private fun ElasticsearchAggregationPlan.rootAggregation(
        afterKey: Map<String, FieldValue>,
        size: Int,
        innerSize: Int,
    ): Aggregation {
        val inner = if (groupSources.isEmpty()) {
            summaryMetricAggregations()
        } else {
            innerGroupAggregations(innerSize, metricAggregations())
        }
        return Aggregation.of { builder ->
            builder.composite { composite ->
                composite.sources(rootSources).size(size).apply {
                    if (afterKey.isNotEmpty()) after(afterKey)
                }
            }.aggregations(elementAggregations(inner))
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
        aggregations = elementAggregations(aggregations)
        if (elements.isNotEmpty()) return aggregations.values.single()
        if (groupSources.isNotEmpty()) return aggregations.getValue(GROUP_AGGREGATION)
        return Aggregation.of { builder ->
            builder.filter { it.matchAll { matchAll -> matchAll } }.aggregations(aggregations)
        }
    }

    /** Wraps [inner] in the nested and filter scope of each element, outermost first. */
    private fun ElasticsearchAggregationPlan.elementAggregations(
        inner: Map<String, Aggregation>,
    ): Map<String, Aggregation> {
        var aggregations = inner
        elements.indices.reversed().forEach { index ->
            val element = elements[index]
            val scope = aggregations
            val filter = Aggregation.of { builder -> builder.filter(element.filter).aggregations(scope) }
            val nested = Aggregation.of { builder ->
                builder.nested { it.path(element.path) }
                    .aggregations(filterAggregationName(index), filter)
            }
            aggregations = mapOf(nestedAggregationName(index) to nested)
        }
        return aggregations
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
                        builder.cardinality { it.field(metric.field).precisionThreshold(DISTINCT_COUNT_PRECISION) }
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

    /**
     * One page of outer buckets: [widest] is the most element groups one outer bucket has held so far, which sizes
     * the next page once [probed], and [exhausted] says the outer composite has no more buckets.
     */
    private class RootPage(
        val rows: List<ObjectNode> = emptyList(),
        val afterKey: Map<String, FieldValue> = emptyMap(),
        val fetched: Int = 0,
        val widest: Int = 0,
        val probed: Boolean = false,
        val exhausted: Boolean = false,
    ) {
        fun shouldStop(limit: Int?): Boolean =
            exhausted || afterKey.isEmpty() || (limit != null && fetched >= limit)
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

/**
 * The `precision_threshold` of a distinct count: Elasticsearch's maximum, below which counts are close to exact. The
 * default (3000) made "executions per day" approximate from 3000 a day. A count above it is still an estimate, which
 * the schema publishes (`approximateMetrics`). The memory is about 8 bytes per distinct value up to the threshold,
 * per bucket.
 */
internal const val DISTINCT_COUNT_PRECISION = 40_000
