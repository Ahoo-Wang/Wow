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
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.isEmpty
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationCompiler
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationPager
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPosition
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.serialization.JsonSerializer
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

abstract class AbstractElasticsearchQueryBackend : QueryBackend {
    abstract val elasticsearchClient: ReactiveElasticsearchClient
    abstract val filterCompiler: AbstractElasticsearchFilterCompiler
    abstract val indexName: String
    protected open val queryBatchSize: Int = DEFAULT_SEARCH_BATCH_SIZE
    protected open val queryKeepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE

    // Stateless collaborators, created once per backend: subclasses supply their configuration through overrides.
    private val queryPager by lazy {
        ElasticsearchQueryPager(elasticsearchClient, indexName, queryBatchSize, queryKeepAlive)
    }
    private val aggregationPager by lazy {
        ElasticsearchAggregationPager(elasticsearchClient, indexName, queryBatchSize, queryKeepAlive)
    }
    private val aggregationCompiler by lazy { ElasticsearchAggregationCompiler(filterCompiler) }

    override val cursorPositions: CursorPositionCodec = ElasticsearchCursorCodec

    override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> {
        val listQuery = query.query
        require(listQuery.limit >= 0) { "limit must be greater than or equal to 0." }
        val compiled = compile(listQuery.filter, listQuery.sort, query)
        if (listQuery.limit == 0 || listQuery.limit > queryBatchSize) {
            return queryPager.search(
                limit = listQuery.limit,
                query = compiled.query,
                sourceFilter = listQuery.sourceFilter(query),
                sort = compiled.sortOptions.searchAfterSort(),
            ).map { it.toObjectNode() }
        }
        return Mono.fromSupplier {
            createSearchRequest(listQuery, compiled, from = 0, size = listQuery.limit, trackTotalHits = false, query)
        }.flatMap(::search).flatMapIterable { it.rows }
    }

    override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> = when (window) {
        is PageWindow.Offset -> Mono.fromSupplier {
            createSearchRequest(
                query = query.query,
                compiled = compile(query.query.filter, query.query.sort, query),
                from = window.offset,
                size = window.limit,
                trackTotalHits = window.withTotal,
                admitted = query,
            )
        }.flatMap(::search).map { if (window.withTotal) it else BackendPage(it.rows) }

        is PageWindow.Keyset -> keysetPage(query, window)
    }

    /** One search_after window, without a point in time; each row's position is its hit's native sort values. */
    private fun keysetPage(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Keyset): Mono<BackendPage> {
        val queryable = query.query
        val compiled = CompiledQuery(
            query = filterCompiler.compile(queryable.filter, query),
            sortOptions = ElasticsearchSortCompiler.compileCursor(queryable.sort, query),
        )
        val request = SearchRequest.of {
            it.index(indexName)
                .allowPartialSearchResults(false)
                .query(compiled.query)
                .size(window.limit)
                .sort(compiled.sortOptions)
                .trackTotalHits { trackHits -> trackHits.enabled(false) }
            @Suppress("UNCHECKED_CAST")
            window.after?.let { after -> it.searchAfter(after.values as List<FieldValue>) }
            if (!queryable.projection.isEmpty()) {
                val sourceFilter = ElasticsearchProjectionCompiler.compile(queryable.projection, query)
                it.source { source -> source.filter(sourceFilter) }
            }
            it
        }
        return Mono.defer { elasticsearchClient.search(request, ObjectNode::class.java) }
            .map { response -> response.requireComplete().toKeysetPage(queryable.sort.size) }
    }

    private fun createSearchRequest(
        query: Queryable<*>,
        compiled: CompiledQuery,
        from: Int,
        size: Int,
        trackTotalHits: Boolean,
        admitted: AdmittedQuery<*>,
    ): SearchRequest {
        val searchRequest = SearchRequest.of {
            it.index(indexName)
                .allowPartialSearchResults(false)
                .query(compiled.query)
                .from(from)
                .size(size)

            it.trackTotalHits { trackHits -> trackHits.enabled(trackTotalHits) }
            if (compiled.sortOptions.isNotEmpty()) {
                it.sort(compiled.sortOptions)
            }
            val sourceFilter = ElasticsearchProjectionCompiler.compile(query.projection, admitted)
            if (!query.projection.isEmpty()) {
                it.source { source -> source.filter(sourceFilter) }
            }
            it
        }
        return searchRequest
    }

    private fun IListQuery.sourceFilter(admitted: AdmittedQuery<*>): SourceFilter? {
        val compiled = ElasticsearchProjectionCompiler.compile(projection, admitted)
        return if (projection.isEmpty()) null else compiled
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

    private fun ResponseBody<ObjectNode>.toKeysetPage(sortSize: Int): BackendPage {
        val hits = hits().hits()
        require(hits.all { it.sort().size == sortSize }) { "Invalid cursor." }
        return BackendPage(
            rows = hits.map { it.toObjectNode() },
            positions = hits.map { CursorPosition(it.sort()) },
        )
    }

    private fun Hit<ObjectNode>.toObjectNode(): ObjectNode =
        checkNotNull(source()) { "Elasticsearch hit [${index()}/${id()}] is missing _source." }

    private fun search(searchRequest: SearchRequest): Mono<BackendPage> {
        return elasticsearchClient.search(searchRequest, ObjectNode::class.java)
            .map { result ->
                result.requireComplete()
                val hits = result.hits()
                BackendPage(hits.hits().map { it.toObjectNode() }, hits.total()?.value() ?: 0)
            }
    }

    override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> {
        return Mono.fromSupplier {
            CountRequest.of {
                it.index(indexName)
                    .query(filterCompiler.compile(query))
            }
        }.flatMap(elasticsearchClient::count).map { it.requireComplete().count() }
    }

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
        aggregationPager.execute(aggregationCompiler.compile(query), window)

    private fun compile(
        filter: FilterExpression,
        sort: List<Sort>,
        admitted: AdmittedQuery<*>,
    ): CompiledQuery =
        CompiledQuery(
            query = filterCompiler.compile(filter, admitted),
            sortOptions = ElasticsearchSortCompiler.compile(sort, admitted),
        )

    private data class CompiledQuery(
        val query: Query,
        val sortOptions: List<SortOptions>,
    )
}

/** Converts an aggregation row built from response values; the core checks that it is standard JSON. */
internal fun Map<*, *>.toObjectNode(): ObjectNode = JsonSerializer.valueToTree(this)
