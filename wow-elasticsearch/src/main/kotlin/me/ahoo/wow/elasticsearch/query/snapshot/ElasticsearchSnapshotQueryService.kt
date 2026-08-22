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

package me.ahoo.wow.elasticsearch.query.snapshot

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.Script
import co.elastic.clients.elasticsearch._types.ScriptLanguage
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.Time
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.JsonData
import co.elastic.clients.util.NamedValue
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchAggregationPager
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldUsage
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.ElasticsearchMappingRefreshResult
import me.ahoo.wow.elasticsearch.query.requireCompleteAggregationResponse
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.convert
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.Arrays
import java.util.PriorityQueue

class ElasticsearchSnapshotQueryService<S : Any>(
    override val namedAggregate: NamedAggregate,
    override val elasticsearchClient: ReactiveElasticsearchClient,
    override val conditionConverter: ConditionConverter<Query> = SnapshotConditionConverter
) : AbstractElasticsearchQueryService<MaterializedSnapshot<S>>(), SnapshotQueryService<S> {
    private var configuredQueryBatchSize: Int = DEFAULT_SEARCH_BATCH_SIZE
    private var configuredQueryKeepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE
    private var configuredIndexMappingResolver: ElasticsearchIndexMappingResolver? =
        conditionConverter
            .takeIf { it === SnapshotConditionConverter }
            ?.let { ElasticsearchIndexMappingResolver(elasticsearchClient) }

    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        conditionConverter: ConditionConverter<Query>,
        queryBatchSize: Int,
        queryKeepAlive: Duration,
    ) : this(namedAggregate, elasticsearchClient, conditionConverter) {
        configuredQueryBatchSize = queryBatchSize
        configuredQueryKeepAlive = queryKeepAlive
    }

    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        conditionConverter: ConditionConverter<Query>,
        queryBatchSize: Int,
        queryKeepAlive: Duration,
        indexMappingResolver: ElasticsearchIndexMappingResolver,
    ) : this(namedAggregate, elasticsearchClient, conditionConverter, queryBatchSize, queryKeepAlive) {
        configuredIndexMappingResolver = indexMappingResolver.takeIf { conditionConverter === SnapshotConditionConverter }
    }

    override val name: String
        get() = ElasticsearchSnapshotStore.NAME
    override val indexName: String = namedAggregate.toSnapshotIndexName()
    protected override val queryBatchSize: Int
        get() = configuredQueryBatchSize
    protected override val queryKeepAlive: Duration
        get() = configuredQueryKeepAlive
    override val indexMappingResolver: ElasticsearchIndexMappingResolver?
        get() = configuredIndexMappingResolver

    fun refreshIndexMapping(): Mono<ElasticsearchMappingRefreshResult> {
        return requireNotNull(indexMappingResolver) {
            "Index mapping resolution is disabled for custom condition converters."
        }.refresh(indexName)
    }

    private val snapshotType = JsonSerializer.typeFactory
        .constructParametricType(
            MaterializedSnapshot::class.java,
            namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, S>().state.aggregateType
        )

    override fun toTypedResult(document: DynamicDocument): MaterializedSnapshot<S> {
        return document.convert(snapshotType)
    }

    override fun resolveCondition(mapping: ElasticsearchIndexMapping, condition: Condition): Condition =
        mapping.resolve(condition)

    override fun resolveSort(mapping: ElasticsearchIndexMapping, sort: List<Sort>): List<Sort> =
        mapping.resolve(sort)

    override fun aggregate(aggregationQuery: AggregationQuery): Flux<Map<String, Any?>> =
        resolveWithMapping { mapping -> aggregationQuery.resolve(mapping) }
            .flatMapMany { resolved ->
                if (resolved.query.groupBy.isEmpty()) {
                    aggregateGlobal(resolved)
                } else {
                    aggregateGrouped(resolved)
                }
            }

    private fun aggregateGlobal(resolved: ResolvedAggregationQuery): Flux<Map<String, Any?>> {
        val metricAggregations = resolved.query.metricAggregations()
        val request = SearchRequest.of {
            it.index(indexName)
                .size(0)
                .allowPartialSearchResults(false)
                .query(resolved.condition)
                .trackTotalHits { trackTotalHits ->
                    trackTotalHits.enabled(resolved.query.metrics.any { metric -> metric is AggregationMetric.Count })
                }
            if (metricAggregations.isNotEmpty()) {
                it.aggregations(metricAggregations)
            }
            it
        }
        return elasticsearchClient.search(request, Map::class.java)
            .map<Map<String, Any?>> { response ->
                response.requireCompleteAggregationResponse()
                buildMap {
                    resolved.query.metrics.forEach { metric ->
                        put(
                            metric.alias,
                            metric.toResultValue(
                                aggregations = response.aggregations(),
                                documentCount = response.hits().total()?.value() ?: 0,
                            ),
                        )
                    }
                }
            }.flux()
    }

    private fun aggregateGrouped(resolved: ResolvedAggregationQuery): Flux<Map<String, Any?>> {
        val effectiveSort = resolved.query.effectiveSort()
        val groupAliases = resolved.query.groupBy.mapTo(hashSetOf(), AggregationGroup::alias)
        val sortByGroupsOnly = effectiveSort.all { it.field in groupAliases }
        val groupsByAlias = resolved.query.groupBy.associateBy(AggregationGroup::alias)
        val orderedGroups = if (sortByGroupsOnly) {
            effectiveSort.map { sort -> checkNotNull(groupsByAlias[sort.field]) to sort.direction }
        } else {
            resolved.query.groupBy.map { it to Sort.Direction.ASC }
        }
        val sources = orderedGroups.map { (group, direction) -> group.toCompositeSource(direction) }
        val buckets = ElasticsearchAggregationPager(
            elasticsearchClient = elasticsearchClient,
            indexName = indexName,
            batchSize = queryBatchSize,
            keepAlive = queryKeepAlive,
        ).search(
            query = resolved.condition,
            sources = sources,
            metrics = resolved.query.metricAggregations(),
            limit = if (sortByGroupsOnly) resolved.query.limit else 0,
        ).map { bucket -> bucket.toResult(resolved.query) }

        return if (sortByGroupsOnly) {
            buckets.take(resolved.query.limit.toLong())
        } else {
            buckets.top(resolved.query.limit, effectiveSort)
        }
    }

    private fun AggregationQuery.resolve(mapping: ElasticsearchIndexMapping?): ResolvedAggregationQuery {
        if (mapping == null) {
            return ResolvedAggregationQuery(conditionConverter.convert(condition), this)
        }
        val resolvedGroups = groupBy.map { group ->
            when (group) {
                is AggregationGroup.Terms -> group.copy(
                    field = mapping.resolve(group.field, ElasticsearchFieldUsage.TERMS)
                )

                is AggregationGroup.Histogram -> group.copy(
                    field = mapping.resolve(group.field, ElasticsearchFieldUsage.NUMERIC)
                )

                is AggregationGroup.DateHistogram -> group.copy(
                    field = mapping.resolve(group.field, ElasticsearchFieldUsage.DATE)
                )
            }
        }
        val resolvedMetrics = metrics.map { metric ->
            when (metric) {
                is AggregationMetric.Count -> metric
                is AggregationMetric.Sum -> metric.copy(
                    field = mapping.resolve(metric.field, ElasticsearchFieldUsage.NUMERIC)
                )

                is AggregationMetric.Avg -> metric.copy(
                    field = mapping.resolve(metric.field, ElasticsearchFieldUsage.NUMERIC)
                )

                is AggregationMetric.Min -> metric.copy(
                    field = mapping.resolve(metric.field, ElasticsearchFieldUsage.NUMERIC)
                )

                is AggregationMetric.Max -> metric.copy(
                    field = mapping.resolve(metric.field, ElasticsearchFieldUsage.NUMERIC)
                )
            }
        }
        return ResolvedAggregationQuery(
            condition = conditionConverter.convert(mapping.resolve(condition)),
            query = copy(groupBy = resolvedGroups, metrics = resolvedMetrics),
        )
    }

    private data class ResolvedAggregationQuery(
        val condition: Query,
        val query: AggregationQuery,
    )
}

private fun AggregationQuery.metricAggregations(): Map<String, Aggregation> = buildMap {
    metrics.forEach { metric ->
        metric.toAggregation()?.let { put(metric.alias, it) }
    }
}

private fun AggregationMetric.toAggregation(): Aggregation? = when (this) {
    is AggregationMetric.Count -> null
    is AggregationMetric.Sum -> Aggregation.of { it.sum { sum -> sum.field(field) } }
    is AggregationMetric.Avg -> Aggregation.of { it.avg { avg -> avg.field(field) } }
    is AggregationMetric.Min -> Aggregation.of { it.min { min -> min.field(field) } }
    is AggregationMetric.Max -> Aggregation.of { it.max { max -> max.field(field) } }
}

private fun AggregationGroup.toCompositeSource(direction: Sort.Direction): NamedValue<CompositeAggregationSource> {
    val sortOrder = if (direction == Sort.Direction.ASC) SortOrder.Asc else SortOrder.Desc
    val source = CompositeAggregationSource.of { source ->
        when (this) {
            is AggregationGroup.Terms -> source.terms {
                it.field(field)
                    .missingBucket(false)
                    .order(sortOrder)
            }

            is AggregationGroup.Histogram -> source.histogram {
                it.interval(interval)
                    .missingBucket(false)
                    .order(sortOrder)
                    .also { histogram ->
                        if (offset != 0.0) {
                            histogram.field(null)
                                .script(offsetScript(field, offset))
                        } else {
                            histogram.field(field)
                        }
                    }
            }

            is AggregationGroup.DateHistogram -> source.dateHistogram {
                it.script(fieldValueScript(field))
                    .missingBucket(false)
                    .order(sortOrder)
                    .timeZone(timeZone)
                    .also { histogram ->
                        val interval = Time.of { time -> time.time(unit.toElasticsearchInterval()) }
                        if (unit == AggregationDateUnit.SECOND) {
                            histogram.fixedInterval(interval)
                        } else {
                            histogram.calendarInterval(interval)
                        }
                    }
            }
        }
    }
    return NamedValue.of(alias, source)
}

private fun offsetScript(field: String, offset: Double): Script = Script.of {
    it.lang(ScriptLanguage.Painless)
        .source { source ->
            source.scriptString("doc[params.field].size() == 0 ? null : doc[params.field].value - params.offset")
        }
        .params("field", JsonData.of(field))
        .params("offset", JsonData.of(offset))
}

private fun fieldValueScript(field: String): Script = Script.of {
    it.lang(ScriptLanguage.Painless)
        .source { source -> source.scriptString("doc[params.field].size() == 0 ? null : doc[params.field].value") }
        .params("field", JsonData.of(field))
}

private fun AggregationDateUnit.toElasticsearchInterval(): String = when (this) {
    AggregationDateUnit.YEAR -> "1y"
    AggregationDateUnit.QUARTER -> "1q"
    AggregationDateUnit.MONTH -> "1M"
    AggregationDateUnit.WEEK -> "1w"
    AggregationDateUnit.DAY -> "1d"
    AggregationDateUnit.HOUR -> "1h"
    AggregationDateUnit.MINUTE -> "1m"
    AggregationDateUnit.SECOND -> "1s"
}

private fun CompositeBucket.toResult(query: AggregationQuery): Map<String, Any?> = buildMap {
    query.groupBy.forEach { group ->
        val value = checkNotNull(key()[group.alias]) {
            "Elasticsearch composite bucket is missing group [${group.alias}]."
        }.toAggregationValue()
        put(
            group.alias,
            if (group is AggregationGroup.Histogram && group.offset != 0.0) {
                (value as Number).toDouble() + group.offset
            } else {
                value
            },
        )
    }
    query.metrics.forEach { metric ->
        put(metric.alias, metric.toResultValue(aggregations(), docCount()))
    }
}

private fun FieldValue.toAggregationValue(): Any? = when {
    isString -> stringValue()
    isLong -> longValue()
    isDouble -> doubleValue()
    isBoolean -> booleanValue()
    isNull -> null
    else -> error("Elasticsearch aggregation group values must be scalar.")
}

private fun AggregationMetric.toResultValue(
    aggregations: Map<String, Aggregate>,
    documentCount: Long,
): Any? = when (this) {
    is AggregationMetric.Count -> documentCount
    is AggregationMetric.Sum -> aggregations.required(alias).sum().value().requireFinite(alias)
    is AggregationMetric.Avg -> aggregations.required(alias).avg().value().finiteOrNull()
    is AggregationMetric.Min -> aggregations.required(alias).min().value().finiteOrNull()
    is AggregationMetric.Max -> aggregations.required(alias).max().value().finiteOrNull()
}

private fun Map<String, Aggregate>.required(alias: String): Aggregate = checkNotNull(get(alias)) {
    "Elasticsearch aggregation response is missing metric [$alias]."
}

private fun Double?.finiteOrNull(): Double? = this?.takeIf(Double::isFinite)

private fun Double?.requireFinite(alias: String): Double {
    check(this?.isFinite() == true) { "Elasticsearch aggregation metric [$alias] returned a non-finite value." }
    return this
}

private fun AggregationQuery.effectiveSort(): List<Sort> = buildList {
    addAll(sort)
    val sortedAliases = sort.mapTo(hashSetOf(), Sort::field)
    groupBy.map(AggregationGroup::alias)
        .filterNot(sortedAliases::contains)
        .forEach { add(Sort(it, Sort.Direction.ASC)) }
}

private fun Flux<Map<String, Any?>>.top(limit: Int, sort: List<Sort>): Flux<Map<String, Any?>> {
    val comparator = aggregationComparator(sort)
    return collect(
        { PriorityQueue(limit + 1, comparator.reversed()) },
        { rows, row ->
            rows += row
            if (rows.size > limit) {
                rows.poll()
            }
        },
    ).flatMapMany { rows -> Flux.fromIterable(rows.sortedWith(comparator)) }
}

private fun aggregationComparator(sort: List<Sort>): Comparator<Map<String, Any?>> = Comparator { left, right ->
    for (criterion in sort) {
        val compared = compareAggregationValues(left[criterion.field], right[criterion.field], criterion.direction)
        if (compared != 0) {
            return@Comparator compared
        }
    }
    0
}

@Suppress("UNCHECKED_CAST")
internal fun compareAggregationValues(left: Any?, right: Any?, direction: Sort.Direction): Int {
    val ascending = when {
        left === right -> 0
        left == null -> -1
        right == null -> 1
        left.isIntegral() && right.isIntegral() -> (left as Number).toLong().compareTo((right as Number).toLong())
        left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
        left is String && right is String -> left.compareUtf8(right)
        left::class == right::class && left is Comparable<*> -> (left as Comparable<Any>).compareTo(right)
        else -> left.toString().compareUtf8(right.toString())
    }
    return if (direction == Sort.Direction.ASC) ascending else -ascending
}

private fun Any?.isIntegral(): Boolean = this is Byte || this is Short || this is Int || this is Long

private fun String.compareUtf8(other: String): Int = Arrays.compareUnsigned(
    encodeToByteArray(),
    other.encodeToByteArray(),
)
