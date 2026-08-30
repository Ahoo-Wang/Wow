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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import co.elastic.clients.elasticsearch.core.search.SourceFilter
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.isEmpty
import me.ahoo.wow.elasticsearch.query.ElasticsearchProjectionConverter.toSourceFilter
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortConverter.toSortOptions
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationCompiler
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationPager
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.schema.ResolvedAggregationQuery
import me.ahoo.wow.query.withUniqueSort
import me.ahoo.wow.serialization.JsonSerializer
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration

abstract class AbstractElasticsearchQueryBackend : QueryBackend {
    abstract val elasticsearchClient: ReactiveElasticsearchClient
    abstract val filterConverter: AbstractElasticsearchFilterConverter
    abstract val indexName: String
    protected open val queryBatchSize: Int = DEFAULT_SEARCH_BATCH_SIZE
    protected open val queryKeepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE
    protected abstract val cursorUniqueField: String
    protected open fun resolve(query: ISingleQuery): Mono<ISingleQuery> = Mono.just(query)

    protected open fun resolve(query: IListQuery): Mono<IListQuery> = Mono.just(query)

    protected open fun resolve(query: IPagedQuery): Mono<IPagedQuery> = Mono.just(query)

    protected open fun resolve(query: ICursorQuery): Mono<ICursorQuery> = Mono.just(query)

    protected open fun resolve(filter: FilterExpression): Mono<FilterExpression> = Mono.just(filter)

    override fun single(query: ISingleQuery): Mono<ObjectNode> {
        return resolve(query).flatMap { resolved ->
            listResolved(
                ListQuery(
                    filter = resolved.filter,
                    projection = resolved.projection,
                    limit = 1,
                    sort = resolved.sort,
                ),
            ).next()
        }
    }

    override fun list(query: IListQuery): Flux<ObjectNode> {
        require(query.limit >= 0) { "limit must be greater than or equal to 0." }
        return resolve(query).flatMapMany(::listResolved)
    }

    private fun listResolved(listQuery: IListQuery): Flux<ObjectNode> {
        val resolved = compile(listQuery.filter, listQuery.sort)
        if (listQuery.limit == 0 || listQuery.limit > queryBatchSize) {
            return ElasticsearchQueryPager(elasticsearchClient, indexName, queryBatchSize, queryKeepAlive).search(
                limit = listQuery.limit,
                query = resolved.query,
                sourceFilter = listQuery.sourceFilter(),
                sort = resolved.sortOptions.searchAfterSort(),
            ).mapNotNull { it.toObjectNode() }
        }
        return Mono.fromSupplier {
            createSearchRequest(
                query = listQuery,
                resolved = resolved,
                from = 0,
                size = listQuery.limit,
                trackTotalHits = false,
            )
        }.flatMap(::search)
            .flatMapIterable { it.list }
    }

    override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> {
        return resolve(query)
            .map { resolved ->
                createSearchRequest(
                    query = resolved,
                    resolved = compile(resolved.filter, resolved.sort),
                    from = resolved.pagination.offset(),
                    size = resolved.pagination.size,
                    trackTotalHits = true,
                )
            }.flatMap(::search)
    }

    override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> {
        val uniqueField = cursorUniqueField
        return resolve(query.withUniqueSort(uniqueField)).flatMap { resolved ->
            val compiled = compile(resolved.filter, resolved.sort)
            elasticsearchClient.search(cursorSearchRequest(resolved, compiled), ObjectNode::class.java)
                .map { response -> response.toCursorPage(resolved) }
        }
    }

    private fun cursorSearchRequest(query: ICursorQuery, resolved: ResolvedQuery): SearchRequest = SearchRequest.of {
        it.index(indexName)
            .query(resolved.query)
            .size(query.size + 1)
            .sort(resolved.sortOptions.withCursorMissing(query.sort))
            .trackTotalHits { trackHits -> trackHits.enabled(false) }
        query.cursor?.let { cursor -> ElasticsearchCursorCodec.decode(cursor, query.sort.size) }
            ?.let(it::searchAfter)
        if (!query.projection.isEmpty()) {
            it.source { source -> source.filter(query.projection.toSourceFilter()) }
        }
        it
    }

    private fun createSearchRequest(
        query: Queryable<*>,
        resolved: ResolvedQuery,
        from: Int,
        size: Int,
        trackTotalHits: Boolean,
    ): SearchRequest {
        val searchRequest = SearchRequest.of {
            it.index(indexName)
                .query(resolved.query)
                .from(from)
                .size(size)

            it.trackTotalHits { trackHits -> trackHits.enabled(trackTotalHits) }
            if (resolved.sortOptions.isNotEmpty()) {
                it.sort(resolved.sortOptions)
            }
            if (!query.projection.isEmpty()) {
                it.source {
                    it.filter(query.projection.toSourceFilter())
                }
            }
            it
        }
        return searchRequest
    }

    private fun IListQuery.sourceFilter(): SourceFilter? {
        return if (projection.isEmpty()) null else projection.toSourceFilter()
    }

    private fun List<SortOptions>.searchAfterSort(): List<SortOptions> {
        return buildList {
            if (this@searchAfterSort.isNotEmpty()) {
                addAll(this@searchAfterSort)
            }
            add(
                SortOptions.of {
                    it.field { field -> field.field("_shard_doc").order(SortOrder.Asc) }
                }
            )
        }
    }

    private fun List<SortOptions>.withCursorMissing(sort: List<Sort>): List<SortOptions> {
        require(size == sort.size)
        return zip(sort) { sortOption, logicalSort ->
            SortOptions.of {
                it.field(
                    sortOption.field().rebuild()
                        .field(sortOption.field().field())
                        .missing(if (logicalSort.direction == Sort.Direction.ASC) "_first" else "_last")
                        .build(),
                )
            }
        }
    }

    private fun ResponseBody<ObjectNode>.toCursorPage(query: ICursorQuery): CursorPage<ObjectNode> {
        val hits = hits().hits()
        require(hits.all { it.sort().size == query.sort.size }) { "Invalid cursor." }
        val returnedHits = hits.take(query.size)
        val nextCursor = if (hits.size > query.size) {
            ElasticsearchCursorCodec.encode(returnedHits.last().sort())
        } else {
            null
        }
        return CursorPage(returnedHits.mapNotNull { it.toObjectNode() }, nextCursor)
    }

    private fun Hit<ObjectNode>.toObjectNode(): ObjectNode? = source()

    private fun search(searchRequest: SearchRequest): Mono<PagedList<ObjectNode>> {
        return elasticsearchClient.search(searchRequest, ObjectNode::class.java)
            .map { result ->
                val hits = result.hits()
                val list = hits.hits().mapNotNull { it.toObjectNode() }
                PagedList(hits.total()?.value() ?: 0, list)
            }
    }

    override fun count(filter: FilterExpression): Mono<Long> {
        return resolve(filter).map { resolved ->
            CountRequest.of {
                it.index(indexName)
                    .query(filterConverter.convert(resolved))
            }
        }.flatMap(elasticsearchClient::count).map { it.count() }
    }

    protected fun executeAggregation(resolved: ResolvedAggregationQuery): Flux<ObjectNode> =
        ElasticsearchAggregationPager(
            elasticsearchClient,
            indexName,
            queryBatchSize,
            queryKeepAlive,
        ).execute(
            ElasticsearchAggregationCompiler(filterConverter).compile(resolved.query, resolved.schema),
        )

    private fun compile(filter: FilterExpression, sort: List<Sort>): ResolvedQuery =
        ResolvedQuery(
            query = filterConverter.convert(filter),
            sortOptions = sort.toSortOptions(),
        )

    private data class ResolvedQuery(
        val query: Query,
        val sortOptions: List<SortOptions>,
    )
}

internal fun Map<*, *>.toObjectNode(): ObjectNode {
    requireStandardJsonValue()
    val node = JsonSerializer.valueToTree<ObjectNode>(this)
    node.requireStandardJson()
    return node
}

private fun Any?.requireStandardJsonValue() {
    when (this) {
        null,
        is String,
        is Boolean,
        is Byte,
        is Short,
        is Int,
        is Long,
        is BigInteger,
        is BigDecimal,
        -> Unit

        is Float -> require(isFinite()) { STANDARD_JSON_SOURCE_ERROR }
        is Double -> require(isFinite()) { STANDARD_JSON_SOURCE_ERROR }
        is JsonNode -> requireStandardJson()
        is Map<*, *> -> forEach { (key, value) ->
            require(key is String) { STANDARD_JSON_SOURCE_ERROR }
            value.requireStandardJsonValue()
        }

        is Iterable<*> -> forEach { it.requireStandardJsonValue() }
        is Array<*> -> forEach { it.requireStandardJsonValue() }
        else -> throw IllegalArgumentException(STANDARD_JSON_SOURCE_ERROR)
    }
}

private fun JsonNode.requireStandardJson() {
    when {
        isObject || isArray -> forEach(JsonNode::requireStandardJson)
        isString || isBoolean || isNull || isIntegralNumber || isBigDecimal -> Unit
        isFloat -> require(floatValue().isFinite()) { STANDARD_JSON_SOURCE_ERROR }
        isDouble -> require(doubleValue().isFinite()) { STANDARD_JSON_SOURCE_ERROR }
        else -> throw IllegalArgumentException(STANDARD_JSON_SOURCE_ERROR)
    }
}

private const val STANDARD_JSON_SOURCE_ERROR = "Elasticsearch source must contain only standard JSON values."
