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
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch.core.SearchRequest
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchConditionConverter
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchAggregationPager
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.elasticsearch.query.ElasticsearchMappingRefreshResult
import me.ahoo.wow.elasticsearch.query.requireCompleteAggregationResponse
import me.ahoo.wow.modeling.annotation.aggregateMetadata
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
    override val conditionConverter: AbstractElasticsearchConditionConverter = SnapshotConditionConverter
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
        conditionConverter: AbstractElasticsearchConditionConverter,
        queryBatchSize: Int,
        queryKeepAlive: Duration,
    ) : this(namedAggregate, elasticsearchClient, conditionConverter) {
        configuredQueryBatchSize = queryBatchSize
        configuredQueryKeepAlive = queryKeepAlive
    }

    constructor(
        namedAggregate: NamedAggregate,
        elasticsearchClient: ReactiveElasticsearchClient,
        conditionConverter: AbstractElasticsearchConditionConverter,
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
    private val aggregationMappingResolver: ElasticsearchIndexMappingResolver by lazy {
        configuredIndexMappingResolver ?: ElasticsearchIndexMappingResolver(elasticsearchClient)
    }

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

    override fun resolveFilter(
        mapping: ElasticsearchIndexMapping,
        filter: me.ahoo.wow.api.query.FilterExpression,
    ): me.ahoo.wow.api.query.FilterExpression = mapping.resolve(filter)

    override fun resolveSort(mapping: ElasticsearchIndexMapping, sort: List<Sort>): List<Sort> =
        mapping.resolve(sort)

    override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> = Flux.defer {
        aggregationMappingResolver.currentOrLoad(indexName)
            .map { mapping ->
                ElasticsearchAggregationCompiler.compile(
                    query = query,
                    mapping = mapping,
                    conditionConverter = conditionConverter,
                    resolveFilter = if (configuredIndexMappingResolver == null) {
                        { filter -> filter }
                    } else {
                        mapping::resolve
                    },
                )
            }.flatMapMany { plan ->
                if (plan.aggregationQuery.groupBy.isEmpty()) aggregateGlobal(plan) else aggregateGrouped(plan)
            }
    }

    private fun aggregateGlobal(plan: ElasticsearchAggregationPlan): Flux<DynamicDocument> {
        val query = plan.aggregationQuery
        val request = SearchRequest.of {
            it.index(indexName)
                .size(0)
                .allowPartialSearchResults(false)
                .query(plan.query)
                .trackTotalHits { trackTotalHits ->
                    trackTotalHits.enabled(
                        query.elements.isEmpty() && query.metrics.any { metric -> metric is AggregationMetric.Count },
                    )
                }.aggregations(plan.wrap(plan.metricAggregations()))
        }
        return elasticsearchClient.search(request, Map::class.java)
            .map<DynamicDocument> { response ->
                response.requireCompleteAggregationResponse()
                val leaf = plan.leaf(response.aggregations())
                val documentCount = if (query.elements.isEmpty()) {
                    response.hits().total()?.value() ?: 0
                } else {
                    checkNotNull(leaf.documentCount) {
                        "Elasticsearch aggregation response is missing the leaf document count."
                    }
                }
                query.toResult(leaf.aggregations, documentCount, plan::metricName)
            }.flux()
    }

    private fun aggregateGrouped(plan: ElasticsearchAggregationPlan): Flux<DynamicDocument> {
        val query = plan.aggregationQuery
        val effectiveSort = query.effectiveSort()
        val groupsByAlias = query.groupBy.associateBy(AggregationGroup::alias)
        val sortByGroupsOnly = effectiveSort.all { it.field in groupsByAlias }
        val orderedGroups = if (sortByGroupsOnly) {
            effectiveSort.map { sort -> checkNotNull(groupsByAlias[sort.field]) to sort.direction }
        } else {
            query.groupBy.map { it to Sort.Direction.ASC }
        }
        val sources = orderedGroups.map { (group, direction) -> plan.compositeSource(group, direction) }
        val buckets = ElasticsearchAggregationPager(
            elasticsearchClient = elasticsearchClient,
            indexName = indexName,
            batchSize = queryBatchSize,
            keepAlive = queryKeepAlive,
        ).search(
            plan = plan,
            sources = sources,
            metrics = plan.metricAggregations(),
            limit = if (sortByGroupsOnly) query.limit else 0,
        ).map { bucket -> bucket.toResult(query, plan::metricName) }

        return if (sortByGroupsOnly) buckets.take(query.limit.toLong()) else buckets.top(query.limit, effectiveSort)
    }
}

private fun AggregationQuery.toResult(
    aggregations: Map<String, Aggregate>,
    documentCount: Long,
    metricName: (AggregationMetric.Numeric) -> String,
): DynamicDocument = linkedMapOf<String, Any?>().apply {
    metrics.forEach { metric -> put(metric.alias, metric.toResultValue(aggregations, documentCount, metricName)) }
}.toDynamicDocument()

private fun CompositeBucket.toResult(
    query: AggregationQuery,
    metricName: (AggregationMetric.Numeric) -> String,
): DynamicDocument = linkedMapOf<String, Any?>().apply {
    query.groupBy.forEach { group ->
        val key = checkNotNull(key()[group.alias]) {
            "Elasticsearch composite bucket is missing group [${group.alias}]."
        }
        put(group.alias, group.toResultValue(key))
    }
    query.metrics.forEach { metric -> put(metric.alias, metric.toResultValue(aggregations(), docCount(), metricName)) }
}.toDynamicDocument()

private fun AggregationGroup.toResultValue(value: FieldValue): Any = when (this) {
    is AggregationGroup.Terms -> value.toAggregationValue()
    is AggregationGroup.Histogram -> (value.toAggregationValue() as Number).toDouble()
    is AggregationGroup.DateHistogram -> when {
        value.isLong -> value.longValue()
        value.isDouble -> value.doubleValue().toLong()
        value.isString -> checkNotNull(value.stringValue().toLongOrNull()) {
            "Elasticsearch date histogram [$alias] must return epoch milliseconds."
        }
        else -> error("Elasticsearch date histogram [$alias] must return epoch milliseconds.")
    }
}

private fun FieldValue.toAggregationValue(): Any = when {
    isString -> stringValue()
    isLong -> longValue()
    isDouble -> doubleValue()
    isBoolean -> booleanValue()
    else -> error("Elasticsearch aggregation group values must be non-null scalars.")
}

private fun AggregationMetric.toResultValue(
    aggregations: Map<String, Aggregate>,
    documentCount: Long,
    metricName: (AggregationMetric.Numeric) -> String,
): Any? = when (this) {
    is AggregationMetric.Count -> documentCount
    is AggregationMetric.Numeric -> {
        check(expression is AggregationExpression.Field) {
            "Elasticsearch supports only field aggregation expressions."
        }
        val aggregate = checkNotNull(aggregations[metricName(this)]) {
            "Elasticsearch aggregation response is missing metric [$alias]."
        }
        val value = when (function) {
            AggregationFunction.SUM -> aggregate.also {
                check(it.isSum) { "Elasticsearch aggregation metric [$alias] must be sum." }
            }.sum().value()
            AggregationFunction.AVG -> aggregate.also {
                check(it.isAvg) { "Elasticsearch aggregation metric [$alias] must be avg." }
            }.avg().value()
            AggregationFunction.MIN -> aggregate.also {
                check(it.isMin) { "Elasticsearch aggregation metric [$alias] must be min." }
            }.min().value()
            AggregationFunction.MAX -> aggregate.also {
                check(it.isMax) { "Elasticsearch aggregation metric [$alias] must be max." }
            }.max().value()
        }
        value?.also {
            check(it.isFinite()) {
                "Elasticsearch aggregation metric [$alias] returned a non-finite value."
            }
        }
    }
}

private fun Flux<DynamicDocument>.top(limit: Int, sort: List<Sort>): Flux<DynamicDocument> {
    val comparator = aggregationComparator(sort)
    return collect(
        { PriorityQueue(limit + 1, comparator.reversed()) },
        { rows, row ->
            rows += row
            if (rows.size > limit) rows.poll()
        },
    ).flatMapMany { rows -> Flux.fromIterable(rows.sortedWith(comparator)) }
}

private fun aggregationComparator(sort: List<Sort>): Comparator<DynamicDocument> = Comparator { left, right ->
    for (criterion in sort) {
        val compared = compareAggregationValues(left[criterion.field], right[criterion.field], criterion.direction)
        if (compared != 0) return@Comparator compared
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
        else -> error(
            "Elasticsearch aggregation sort values must have compatible scalar types " +
                "[${left::class.qualifiedName}, ${right::class.qualifiedName}].",
        )
    }
    return if (direction == Sort.Direction.ASC) ascending else -ascending
}

private fun Any?.isIntegral(): Boolean = this is Byte || this is Short || this is Int || this is Long

private fun String.compareUtf8(other: String): Int = Arrays.compareUnsigned(
    encodeToByteArray(),
    other.encodeToByteArray()
)
