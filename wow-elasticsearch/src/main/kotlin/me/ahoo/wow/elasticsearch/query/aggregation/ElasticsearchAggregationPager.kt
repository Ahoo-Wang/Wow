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
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchPointInTime
import me.ahoo.wow.elasticsearch.query.toObjectNode
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.PriorityQueue
import kotlin.math.min

private const val ROOT_AGGREGATION = "__wow_aggregation"
private const val GROUP_AGGREGATION = "__wow_groups"

internal class ElasticsearchAggregationPager(
    private val client: ReactiveElasticsearchClient,
    indexName: String,
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

    fun execute(plan: ElasticsearchAggregationPlan): Flux<ObjectNode> = pointInTime.use { pit ->
        if (plan.groupSources.isEmpty()) {
            search(plan, pit, afterKey = emptyMap(), pageSize = 0)
                .map { response -> response.summary(plan) }
        } else {
            grouped(plan, pit)
        }
    }

    private fun grouped(
        plan: ElasticsearchAggregationPlan,
        pit: ElasticsearchPointInTime.Session,
    ): Flux<ObjectNode> {
        val pages = searchPage(plan, pit)
            .expand { page ->
                if (page.shouldStop(plan)) {
                    Mono.empty()
                } else {
                    searchPage(plan, pit, page.afterKey, page.fetched)
                }
            }
        val rows = pages.concatMapIterable({ it.rows }, 1)
        if (!plan.metricSorted) return rows

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
    ): Mono<AggregationPage> {
        val bucketWidth = 1 + plan.metrics.count { it is ElasticsearchAggregationMetric.Any }
        val pageCapacity = (batchSize / bucketWidth).coerceAtLeast(1)
        val pageSize = if (plan.metricSorted) pageCapacity else min(pageCapacity, plan.limit - fetched)
        return search(plan, pit, afterKey, pageSize).map { response ->
            val composite = response.innermost(plan).getValue(GROUP_AGGREGATION).composite()
            val rows = composite.buckets().array().map { it.toRow(plan) }
            AggregationPage(rows, composite.afterKey(), fetched + rows.size)
        }
    }

    private fun search(
        plan: ElasticsearchAggregationPlan,
        pit: ElasticsearchPointInTime.Session,
        afterKey: Map<String, FieldValue>,
        pageSize: Int,
    ): Mono<ResponseBody<Map<*, *>>> = Mono.defer {
        val request = SearchRequest.of {
            it.query(plan.rootQuery)
                .size(0)
                .trackTotalHits { track -> track.enabled(false) }
                .pit { pointInTime ->
                    pointInTime.id(pit.id).keepAlive { keepAlive -> keepAlive.time(this.pointInTime.keepAliveValue) }
                }
                .runtimeMappings(plan.runtimeMappings)
                .aggregations(ROOT_AGGREGATION, plan.aggregation(afterKey, pageSize))
        }
        client.search(request, Map::class.java)
    }.doOnNext { pit.update(it.pitId()) }

    private fun ElasticsearchAggregationPlan.aggregation(
        afterKey: Map<String, FieldValue>,
        pageSize: Int,
    ): Aggregation {
        var aggregations = if (groupSources.isEmpty()) {
            metricAggregations()
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
                is ElasticsearchAggregationMetric.Count -> Unit
                is ElasticsearchAggregationMetric.Any -> put(
                    metric.alias,
                    Aggregation.of { builder ->
                        builder.terms { terms -> terms.field(metric.field).size(1) }
                    },
                )

                is ElasticsearchAggregationMetric.Numeric -> {
                    put(
                        metric.alias,
                        Aggregation.of { builder ->
                            when (metric.function) {
                                AggregationFunction.SUM -> builder.sum { it.field(metric.field) }
                                AggregationFunction.AVG -> builder.avg { it.field(metric.field) }
                                AggregationFunction.MIN -> builder.min { it.field(metric.field) }
                                AggregationFunction.MAX -> builder.max { it.field(metric.field) }
                            }
                        },
                    )
                    put(
                        metric.valueCountAlias,
                        Aggregation.of { builder -> builder.valueCount { it.field(metric.field) } },
                    )
                }
            }
        }
    }

    private fun ResponseBody<Map<*, *>>.summary(plan: ElasticsearchAggregationPlan): ObjectNode {
        val scope = aggregations().getValue(ROOT_AGGREGATION).let { root ->
            if (plan.elements.isEmpty()) root.filter() else root.innermostScope(plan)
        }
        return plan.toRow(scope.docCount(), scope.aggregations())
    }

    private fun ResponseBody<Map<*, *>>.innermost(plan: ElasticsearchAggregationPlan): Map<String, Aggregate> {
        if (plan.elements.isEmpty()) {
            return mapOf(GROUP_AGGREGATION to aggregations().getValue(ROOT_AGGREGATION))
        }
        var aggregations = aggregations()
        plan.elements.indices.forEach { index ->
            val nested = aggregations.getValue(if (index == 0) ROOT_AGGREGATION else nestedAggregationName(index))
                .nested()
            val filter = nested.aggregations().getValue(filterAggregationName(index)).filter()
            aggregations = filter.aggregations()
        }
        return aggregations
    }

    private fun Aggregate.innermostScope(
        plan: ElasticsearchAggregationPlan,
    ): co.elastic.clients.elasticsearch._types.aggregations.FilterAggregate {
        var aggregations = nested().aggregations()
        var scope: co.elastic.clients.elasticsearch._types.aggregations.FilterAggregate? = null
        plan.elements.indices.forEach { index ->
            scope = aggregations.getValue(filterAggregationName(index)).filter()
            aggregations = scope!!.aggregations()
            if (index + 1 < plan.elements.size) {
                aggregations = aggregations.getValue(nestedAggregationName(index + 1)).nested().aggregations()
            }
        }
        return requireNotNull(scope)
    }

    private fun CompositeBucket.toRow(plan: ElasticsearchAggregationPlan): ObjectNode {
        val row = key().mapValuesTo(linkedMapOf()) { (_, value) -> value.nativeValue() }
        plan.metrics.forEach { metric -> row[metric.alias] = metric.value(docCount(), aggregations()) }
        return row.toObjectNode()
    }

    private fun ElasticsearchAggregationPlan.toRow(
        docCount: Long,
        aggregations: Map<String, Aggregate>,
    ): ObjectNode = metrics.associateTo(linkedMapOf()) { metric ->
        metric.alias to metric.value(docCount, aggregations)
    }.toObjectNode()

    private fun ElasticsearchAggregationMetric.value(
        docCount: Long,
        aggregations: Map<String, Aggregate>,
    ): Any? = when (this) {
        is ElasticsearchAggregationMetric.Count -> docCount
        is ElasticsearchAggregationMetric.Any -> aggregations.getValue(alias).anyValue(alias)
        is ElasticsearchAggregationMetric.Numeric -> numericValue(aggregations)
    }

    private fun Aggregate.anyValue(alias: String): Any? = when {
        isSterms -> sterms().buckets().array().firstOrNull()?.key()?.nativeValue()
        isLterms -> lterms().buckets().array().firstOrNull()?.let {
            it.keyAsString()?.toBooleanStrictOrNull() ?: it.key()
        }
        isDterms -> dterms().buckets().array().firstOrNull()?.key()
        isUmterms -> null
        else -> error("Aggregation ANY metric [$alias] returned unsupported Elasticsearch aggregate [${_kind()}].")
    }

    private fun ElasticsearchAggregationMetric.Numeric.numericValue(
        aggregations: Map<String, Aggregate>,
    ): Double? {
        if (aggregations.getValue(valueCountAlias).valueCount().value() == 0.0) return null
        val value = when (function) {
            AggregationFunction.SUM -> aggregations.getValue(alias).sum().value()
            AggregationFunction.AVG -> aggregations.getValue(alias).avg().value()
            AggregationFunction.MIN -> aggregations.getValue(alias).min().value()
            AggregationFunction.MAX -> aggregations.getValue(alias).max().value()
        }
        require(value != null && value.isFinite()) { "Aggregation metric [$alias] must be finite." }
        return value
    }

    private fun FieldValue.nativeValue(): Any? = when {
        isString -> stringValue()
        isLong -> longValue()
        isDouble -> doubleValue()
        isBoolean -> booleanValue()
        isNull -> null
        else -> error("Unsupported Elasticsearch aggregation key [${_kind()}].")
    }

    private data class AggregationPage(
        val rows: List<ObjectNode>,
        val afterKey: Map<String, FieldValue>,
        val fetched: Int,
    ) {
        fun shouldStop(plan: ElasticsearchAggregationPlan): Boolean {
            if (afterKey.isEmpty() || rows.isEmpty()) return true
            return !plan.metricSorted && fetched >= plan.limit
        }
    }
}

internal fun selectTopRows(
    rows: Iterable<ObjectNode>,
    sort: List<Sort>,
    limit: Int,
): List<ObjectNode> = BoundedTopRows(sort, limit).apply { rows.forEach(::add) }.result()

private class BoundedTopRows(
    sort: List<Sort>,
    private val limit: Int,
    private val groupAliases: List<String> = emptyList(),
) {
    private val groupIndexes = groupAliases.withIndex().associate { (index, alias) -> alias to index }
    private val currentGroupOrder = LongArray(groupAliases.size)
    private var previous: ObjectNode? = null
    private var sequence = 0L
    private val comparator = rankedRowComparator(sort, groupIndexes)
    private val rows = PriorityQueue(comparator.reversed())

    fun add(row: ObjectNode) {
        previous?.let { previous ->
            val firstDifference = groupAliases.indexOfFirst { previous[it] != row[it] }
            if (firstDifference >= 0) {
                currentGroupOrder.fill(sequence, firstDifference)
            }
        }
        previous = row
        val rankedRow = RankedRow(row, currentGroupOrder.copyOf())
        sequence++
        if (rows.size < limit) {
            rows += rankedRow
        } else if (comparator.compare(rankedRow, rows.peek()) < 0) {
            rows.poll()
            rows += rankedRow
        }
    }

    fun result(): List<ObjectNode> = rows.sortedWith(comparator).map(RankedRow::row)
}

private data class RankedRow(
    val row: ObjectNode,
    val groupOrder: LongArray,
)

private fun rankedRowComparator(
    sort: List<Sort>,
    groupIndexes: Map<String, Int>,
): Comparator<RankedRow> = Comparator { left, right ->
    sort.firstNotNullOfOrNull { field ->
        val comparison = groupIndexes[field.field]?.let { index ->
            left.groupOrder[index].compareTo(right.groupOrder[index])
        } ?: compareValues(left.row[field.field], right.row[field.field])
            .let { if (field.direction == Sort.Direction.ASC) it else -it }
        comparison.takeIf { it != 0 }
    } ?: 0
}

private fun compareValues(left: JsonNode?, right: JsonNode?): Int {
    val leftValue = left.toSortValue()
    val rightValue = right.toSortValue()
    return when {
        leftValue === rightValue -> 0
        leftValue == null -> -1
        rightValue == null -> 1
        leftValue is Long && rightValue is Long -> leftValue.compareTo(rightValue)
        leftValue is Number && rightValue is Number -> leftValue.toDouble().compareTo(rightValue.toDouble())
        leftValue is String && rightValue is String -> leftValue.compareTo(rightValue)
        leftValue is Boolean && rightValue is Boolean -> leftValue.compareTo(rightValue)
        else -> incomparableValues(left, right)
    }
}

private fun JsonNode?.toSortValue(): Any? = when {
    this == null || isNull -> null
    isIntegralNumber -> longValue()
    isNumber -> doubleValue()
    isString -> stringValue()
    isBoolean -> booleanValue()
    else -> this
}

private fun incomparableValues(left: JsonNode?, right: JsonNode?): Nothing =
    error(
        "Aggregation sort values must have comparable types, " +
            "but were [${left?.nodeType}] and [${right?.nodeType}].",
    )

private fun nestedAggregationName(index: Int): String = "__wow_element_$index"

private fun filterAggregationName(index: Int): String = "__wow_element_filter_$index"
