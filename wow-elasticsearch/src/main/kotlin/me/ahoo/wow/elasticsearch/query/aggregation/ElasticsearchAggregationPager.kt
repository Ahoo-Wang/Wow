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
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchPointInTime
import me.ahoo.wow.elasticsearch.query.requireComplete
import org.reactivestreams.Publisher
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

    fun execute(plan: ElasticsearchAggregationPlan): Flux<ObjectNode> = Flux.defer {
        val firstAggregation = plan.aggregation(emptyMap(), if (plan.groupSources.isEmpty()) 0 else plan.pageSize(0))
        if (plan.groupSources.isEmpty()) {
            search(plan, null, firstAggregation).map { response -> response.summary(plan) }.flux()
        } else {
            pointInTime.use { pit -> grouped(plan, pit, firstAggregation) }
        }
    }

    private fun grouped(
        plan: ElasticsearchAggregationPlan,
        pit: ElasticsearchPointInTime.Session,
        firstAggregation: Aggregation,
    ): Flux<ObjectNode> {
        val pages = searchPage(plan, pit, aggregation = firstAggregation)
            .expand { page ->
                if (page.shouldStop(plan)) {
                    Mono.empty()
                } else {
                    searchPage(plan, pit, page.afterKey, page.fetched)
                }
            }
        val rows = if (plan.dense == null) {
            pages.concatMap({ it.rows }, 1)
        } else {
            var previousKey: Long? = null
            pages.concatMap(
                { page ->
                    val bridgeGaps = previousKey?.let { prev ->
                        page.firstKey?.let { next -> fillGapRows(prev, next, plan) }
                    } ?: Flux.empty<ObjectNode>()
                    page.lastKey?.let { previousKey = it }
                    Flux.concat(bridgeGaps, page.rows)
                },
                1,
            )
        }
        if (!plan.metricSorted) {
            // having filters client-side, so a page can yield more survivors than the remaining
            // limit; dense fills likewise emit more rows than the server page size — both paths
            // cap at the limit client-side, the no-fill no-having path stays composite-capped
            return if (plan.having != null || plan.dense != null) rows.take(plan.limit.toLong()) else rows
        }

        return rows.collect(
            { BoundedTopRows(plan.effectiveSort, plan.limit, plan.groupSources.map { it.name() }) },
            BoundedTopRows::add,
        ).flatMapMany { Flux.fromIterable(it.result()) }
    }

    private fun searchPage(
        plan: ElasticsearchAggregationPlan,
        pit: ElasticsearchPointInTime.Session,
        afterKey: Map<String, FieldValue> = emptyMap(),
        fetched: Int = 0,
        aggregation: Aggregation = plan.aggregation(afterKey, plan.pageSize(fetched)),
    ): Mono<AggregationPage> {
        return search(plan, pit, aggregation).map { response ->
            val composite = response.innermost(plan).getValue(GROUP_AGGREGATION).composite()
            val buckets = composite.buckets().array()
            val denseAlias = plan.dense?.alias
            fun bucketKey(bucket: CompositeBucket): Long? =
                denseAlias?.let { bucket.key().getValue(it) }?.let { it.nativeValue() as Long }

            val firstKey = buckets.firstOrNull()?.let(::bucketKey)
            val lastKey = buckets.lastOrNull()?.let(::bucketKey)
            // composite never emits empty buckets, so dense gaps between consecutive ACTUAL buckets
            // of one page are filled here against raw (pre-having) keys; grouped() bridges the gap
            // between the previous page's last bucket and this page's first bucket. Fill rows stay
            // lazy Flux segments: one gap may span more buckets than the client heap can hold, so
            // generation must be bounded by downstream demand (take / top-N collection)
            var previousBucketKey: Long? = null
            val segments = ArrayList<Publisher<ObjectNode>>(buckets.size * 2)
            var realRowCount = 0
            buckets.forEach { bucket ->
                val key = bucketKey(bucket)
                val previousKey = previousBucketKey
                if (key != null && previousKey != null) {
                    segments += fillGapRows(previousKey, key, plan)
                }
                if (key != null) {
                    previousBucketKey = key
                }
                val row = bucket.toRow(plan)
                if (plan.having == null || row.matchesHaving(plan.having)) {
                    realRowCount++
                    segments += Mono.just(row)
                }
            }
            AggregationPage(
                Flux.concat(segments),
                realRowCount,
                composite.afterKey(),
                fetched + realRowCount,
                firstKey,
                lastKey
            )
        }
    }

    private fun ElasticsearchAggregationPlan.pageSize(fetched: Int): Int {
        val bucketWidth = 1 + metrics.count { it is ElasticsearchAggregationMetric.Any } +
            metrics.count { it.filter != null }
        val pageCapacity = (batchSize / bucketWidth).coerceAtLeast(1)
        return if (metricSorted || having != null) pageCapacity else min(pageCapacity, limit - fetched)
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
        val rows: Flux<ObjectNode>,
        private val realRowCount: Int,
        val afterKey: Map<String, FieldValue>,
        val fetched: Int,
        val firstKey: Long?,
        val lastKey: Long?,
    ) {
        fun shouldStop(plan: ElasticsearchAggregationPlan): Boolean {
            if (afterKey.isEmpty()) return true
            // a fully-filtered page is not bucket exhaustion; only an empty after key stops paging
            if (plan.having == null && realRowCount == 0) return true
            // fetched counts having-surviving REAL bucket rows: dense fill rows are streamed on
            // demand and cannot be counted eagerly, so a dense group sort may keep paging until
            // enough real buckets arrive — the client-side take(limit) still caps the output
            return !plan.metricSorted && fetched >= plan.limit
        }
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
