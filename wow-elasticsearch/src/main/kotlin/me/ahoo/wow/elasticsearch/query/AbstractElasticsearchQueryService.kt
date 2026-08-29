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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import co.elastic.clients.elasticsearch.core.search.SourceFilter
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.isEmpty
import me.ahoo.wow.elasticsearch.query.ElasticsearchProjectionConverter.toSourceFilter
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortConverter.toSortOptions
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationCompiler
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationPager
import me.ahoo.wow.query.CursorTokenCodec
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.schema.ResolvedAggregationQuery
import me.ahoo.wow.query.withUniqueSort
import me.ahoo.wow.serialization.JsonSerializer
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Duration

abstract class AbstractElasticsearchQueryService<R : Any> : QueryService<R> {
    abstract val elasticsearchClient: ReactiveElasticsearchClient
    abstract val filterConverter: AbstractElasticsearchFilterConverter
    abstract val indexName: String
    protected open val queryBatchSize: Int = DEFAULT_SEARCH_BATCH_SIZE
    protected open val queryKeepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE
    protected open val cursorUniqueField: String? = null
    protected open val cursorTokenCodec: CursorTokenCodec? = null
    abstract fun toTypedResult(document: DynamicDocument): R

    protected open fun resolve(query: ISingleQuery): Mono<ISingleQuery> = Mono.just(query)

    protected open fun resolve(query: IListQuery): Mono<IListQuery> = Mono.just(query)

    protected open fun resolve(query: IPagedQuery): Mono<IPagedQuery> = Mono.just(query)

    protected open fun resolve(query: ICursorQuery): Mono<ICursorQuery> = Mono.just(query)

    protected open fun resolve(filter: FilterExpression): Mono<FilterExpression> = Mono.just(filter)

    override fun single(singleQuery: ISingleQuery): Mono<R> {
        return dynamicSingle(singleQuery).map { toTypedResult(it) }
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        return resolve(singleQuery).flatMap { resolved ->
            dynamicListResolved(
                ListQuery(
                    filter = resolved.filter,
                    projection = resolved.projection,
                    limit = 1,
                    sort = resolved.sort,
                ),
            ).next()
        }
    }

    override fun list(listQuery: IListQuery): Flux<R> {
        return dynamicList(listQuery).map { toTypedResult(it) }
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        require(listQuery.limit >= 0) { "limit must be greater than or equal to 0." }
        return resolve(listQuery).flatMapMany(::dynamicListResolved)
    }

    private fun dynamicListResolved(listQuery: IListQuery): Flux<DynamicDocument> {
        val resolved = compile(listQuery.filter, listQuery.sort)
        if (listQuery.limit == 0 || listQuery.limit > queryBatchSize) {
            return ElasticsearchQueryPager(elasticsearchClient, indexName, queryBatchSize, queryKeepAlive).search(
                limit = listQuery.limit,
                query = resolved.query,
                sourceFilter = listQuery.sourceFilter(),
                sort = resolved.sortOptions.searchAfterSort(),
            ).mapNotNull { it.toDynamicDocument() }
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

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<R>> {
        return dynamicPaged(pagedQuery).map {
            PagedList(
                total = it.total,
                list = it.list.map { doc ->
                    toTypedResult(doc)
                }
            )
        }
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        return resolve(pagedQuery)
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

    override fun cursor(query: ICursorQuery): Mono<CursorPage<R>> = dynamicCursor(query).map { page ->
        CursorPage(page.list.map(::toTypedResult), page.nextCursor)
    }

    override fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<DynamicDocument>> {
        val uniqueField = cursorUniqueField
            ?: return Mono.error(UnsupportedOperationException("Cursor query is not supported."))
        val tokenCodec = cursorTokenCodec
            ?: return Mono.error(UnsupportedOperationException("Cursor query is not supported."))
        return resolve(query.withUniqueSort(uniqueField))
            .flatMap { resolved ->
                elasticsearchClient.search(
                    cursorSearchRequest(resolved, compile(resolved.filter, resolved.sort)),
                    Map::class.java,
                ).map { response -> response.toCursorPage(resolved, tokenCodec) }
            }
    }

    private fun cursorSearchRequest(query: ICursorQuery, resolved: ResolvedQuery): SearchRequest = SearchRequest.of {
        it.index(indexName)
            .query(resolved.query)
            .size(query.size + 1)
            .sort(resolved.sortOptions.withCursorMissing(query.sort))
            .trackTotalHits { hits -> hits.enabled(false) }
        query.cursor?.let { cursor ->
            ElasticsearchCursorCodec.decode(
                requireNotNull(cursorTokenCodec),
                cursor,
                query.sort.size,
            )
        }?.let(it::searchAfter)
        if (!query.projection.isEmpty()) {
            it.source { source -> source.filter(query.projection.toSourceFilter()) }
        }
        it
    }

    private fun ResponseBody<Map<*, *>>.toCursorPage(
        query: ICursorQuery,
        tokenCodec: CursorTokenCodec,
    ): CursorPage<DynamicDocument> {
        val returned = hits().hits().take(query.size)
        val nextCursor = if (hits().hits().size > query.size) {
            val sortValues = returned.last().sort()
            require(sortValues.size == query.sort.size) { "Invalid cursor." }
            ElasticsearchCursorCodec.encode(tokenCodec, sortValues)
        } else {
            null
        }
        return CursorPage(
            list = returned.mapNotNull { it.toDynamicDocument() },
            nextCursor = nextCursor,
        )
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

    private fun List<SortOptions>.withCursorMissing(sort: List<Sort>): List<SortOptions> =
        mapIndexed { index, option ->
            SortOptions.of {
                it.field(
                    option.field().rebuild()
                        .field(option.field().field())
                        .missing(if (sort[index].direction == Sort.Direction.ASC) "_first" else "_last")
                        .build(),
                )
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun Hit<Map<*, *>>.toDynamicDocument(): DynamicDocument? {
        return source()?.let { (it as MutableMap<String, Any?>).toDynamicDocument() }
    }

    private fun search(searchRequest: SearchRequest): Mono<PagedList<DynamicDocument>> {
        return elasticsearchClient.search(searchRequest, Map::class.java)
            .map { result ->
                val hits = result.hits()
                val list = hits.hits().mapNotNull { it.toDynamicDocument() }
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

    protected fun executeAggregation(resolved: ResolvedAggregationQuery): Flux<DynamicDocument> =
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

internal object ElasticsearchCursorCodec {
    fun encode(tokenCodec: CursorTokenCodec, values: List<FieldValue>): String = try {
        require(values.size <= me.ahoo.wow.api.query.AggregationQuery.MAX_SORT_FIELDS)
        tokenCodec.encode(JsonSerializer.writeValueAsBytes(values.map(FieldValue::toCursorValue)))
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid cursor.")
    }

    fun decode(tokenCodec: CursorTokenCodec, cursor: String, expectedSize: Int): List<FieldValue> = try {
        require(expectedSize in 1..me.ahoo.wow.api.query.AggregationQuery.MAX_SORT_FIELDS)
        val values = JsonSerializer.readTree(tokenCodec.decode(cursor))
        require(values.isArray && values.size() == expectedSize)
        values.toList().map(JsonNode::toFieldValue)
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid cursor.")
    }
}

private fun JsonNode.toFieldValue(): FieldValue = when {
    isNull -> FieldValue.NULL
    isString -> FieldValue.of(asString())
    isBoolean -> FieldValue.of(booleanValue())
    isIntegralNumber -> FieldValue.of(longValue())
    isNumber -> FieldValue.of(doubleValue())
    else -> throw IllegalArgumentException("Cursor sort value must be scalar.")
}

private fun FieldValue.toCursorValue(): JsonNode = when {
    isNull -> JsonNodeFactory.instance.nullNode()
    isString -> JsonNodeFactory.instance.stringNode(stringValue())
    isBoolean -> JsonNodeFactory.instance.booleanNode(booleanValue())
    isLong -> JsonNodeFactory.instance.numberNode(longValue())
    isDouble -> JsonNodeFactory.instance.numberNode(doubleValue())
    else -> throw IllegalArgumentException("Elasticsearch cursor sort value must be scalar.")
}
