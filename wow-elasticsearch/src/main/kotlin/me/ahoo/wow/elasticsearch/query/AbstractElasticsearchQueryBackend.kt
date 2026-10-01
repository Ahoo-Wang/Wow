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
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import co.elastic.clients.elasticsearch.core.search.SourceFilter
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Queryable
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
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.checkExecution
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.serialization.JsonSerializer
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/**
 * A query backend over one index. Before the aggregate's first write its index does not exist yet; every read of a
 * missing index answers nothing (no rows, a count of `0`, no groups), as the snapshot store's load does, rather than
 * failing.
 */
abstract class AbstractElasticsearchQueryBackend : QueryBackend {
    abstract val elasticsearchClient: ReactiveElasticsearchClient
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

    override val cursorPositions: CursorPositionCodec = ElasticsearchCursorCodec

    override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> =
        streamIndex(query).orMissing(query) { Flux.empty() }

    private fun streamIndex(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> {
        val listQuery = query.query
        require(listQuery.limit >= 0) { "limit must be greater than or equal to 0." }
        if (listQuery.limit == 0 || listQuery.limit > queryBatchSize) {
            return queryPager.search(
                limit = listQuery.limit,
                query = ElasticsearchFilterCompiler.compile(listQuery.filter, query),
                sourceFilter = listQuery.projection.sourceFilter(query),
                sort = ElasticsearchSortCompiler.compile(listQuery.sort, query).searchAfterSort(),
            ).map { it.toObjectNode() }
        }
        return Mono.fromSupplier {
            searchRequest(query, listQuery, ElasticsearchSortCompiler.compile(listQuery.sort, query)) {
                it.from(0).size(listQuery.limit).trackTotalHits { trackHits -> trackHits.enabled(false) }
            }
        }.flatMap(::search).flatMapIterable { it.rows }
    }

    override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> =
        pageIndex(query, window).orMissing(query) {
            Mono.fromSupplier {
                when (window) {
                    is PageWindow.Offset -> if (window.withTotal) {
                        BackendPage(
                            emptyList(),
                            0
                        )
                    } else {
                        BackendPage(emptyList())
                    }
                    is PageWindow.Keyset -> BackendPage(emptyList(), positions = emptyList())
                }
            }
        }

    private fun pageIndex(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> = when (window) {
        is PageWindow.Offset -> Mono.fromSupplier {
            searchRequest(query, query.query, ElasticsearchSortCompiler.compile(query.query.sort, query)) {
                it.from(window.offset).size(window.limit)
                    .trackTotalHits { trackHits -> trackHits.enabled(window.withTotal) }
            }
        }.flatMap(::search).map { if (window.withTotal) it else BackendPage(it.rows) }

        is PageWindow.Keyset -> keysetPage(query, window)
    }

    /** One search_after window, without a point in time; each row's position is its hit's native sort values. */
    private fun keysetPage(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Keyset): Mono<BackendPage> {
        val queryable = query.query
        val request = searchRequest(query, queryable, ElasticsearchSortCompiler.compileCursor(queryable.sort, query)) {
            it.size(window.limit).trackTotalHits { trackHits -> trackHits.enabled(false) }
            @Suppress("UNCHECKED_CAST")
            window.after?.let { after -> it.searchAfter(after.values as List<FieldValue>) }
            it
        }
        return Mono.defer { elasticsearchClient.search(request, ObjectNode::class.java) }
            .map { response -> response.requireComplete().toKeysetPage(queryable.sort.size) }
    }

    /**
     * A search of [queryable] over this index: the invariants every search request carries (the index, no partial
     * results, the compiled filter, [sort] and the projection's source filter), then the caller's window.
     */
    private fun searchRequest(
        admitted: AdmittedQuery<*>,
        queryable: Queryable<*>,
        sort: List<SortOptions>,
        window: (SearchRequest.Builder) -> SearchRequest.Builder,
    ): SearchRequest = SearchRequest.of {
        it.index(indexName)
            .allowPartialSearchResults(false)
            .query(ElasticsearchFilterCompiler.compile(queryable.filter, admitted))
        if (sort.isNotEmpty()) it.sort(sort)
        queryable.projection.sourceFilter(admitted)?.let { sourceFilter ->
            it.source { source -> source.filter(sourceFilter) }
        }
        window(it)
    }

    private fun Projection.sourceFilter(admitted: AdmittedQuery<*>): SourceFilter? =
        if (isEmpty()) null else ElasticsearchProjectionCompiler.compile(this, admitted)

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
        checkExecution(hits.all { it.sort().size == sortSize }) { "Elasticsearch hit sort values must match the sort." }
        return BackendPage(
            rows = hits.map { it.toObjectNode() },
            positions = hits.map { CursorPosition(it.sort()) },
        )
    }

    private fun Hit<ObjectNode>.toObjectNode(): ObjectNode =
        source() ?: throw QueryExecutionException("Elasticsearch hit is missing _source.")

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
                    .query(ElasticsearchFilterCompiler.compile(query))
            }
        }.flatMap(elasticsearchClient::count).map { it.requireComplete().count() }
            .orMissing(query) { Mono.just(0L) }
    }

    /** A missing index has no groups; without any, the core emits the empty summary, as it does over no records. */
    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
        aggregationPager.execute(ElasticsearchAggregationCompiler.compile(query), window)
            .orMissing(query) { Flux.empty() }

    /**
     * [missing] when the index does not exist. A query admitted against the provisional schema of a missing index
     * that finds the index (created since, by the first write) is refused: its bindings for the paths the templates
     * did not map are the logical model's, not the index's. The provisional schema expires within seconds.
     */
    private fun <T : Any> Flux<T>.orMissing(query: AdmittedQuery<*>, missing: () -> Flux<T>): Flux<T> =
        (if (query.provisional) thenMany(Flux.error<T> { createdSinceAdmission() }) else this)
            .onErrorResume(Throwable::isIndexNotFound) { missing() }

    private fun <T : Any> Mono<T>.orMissing(query: AdmittedQuery<*>, missing: () -> Mono<T>): Mono<T> =
        (if (query.provisional) then(Mono.error<T> { createdSinceAdmission() }) else this)
            .onErrorResume(Throwable::isIndexNotFound) { missing() }

    private fun createdSinceAdmission(): Throwable = QuerySchemaUnavailableException(
        "Elasticsearch index [$indexName] was created after the query was admitted against the schema of its " +
            "templates; retry once its query schema follows the index."
    )
}

/** Converts an aggregation row built from response values; the core checks that it is standard JSON. */
internal fun Map<*, *>.toObjectNode(): ObjectNode = JsonSerializer.valueToTree(this)
