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

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.SourceFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.isEmpty
import me.ahoo.wow.elasticsearch.ElasticsearchFailures
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationCompiler
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationPager
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/**
 * How a query backend reads one kind of document of an index as the records of a query model: a snapshot
 * or an event stream.
 */
internal interface ElasticsearchQueryDocuments {
    /** The document fields that end a list's or an offset page's sort, ascending, so its order is total. */
    val tieBreakers: List<String>

    /** The store's own fields, which no record shows: a search never fetches them. */
    val storeFields: List<String>

    /** The record of [source]; a field a source filter left out is absent. */
    fun record(source: ObjectNode): ObjectNode
}

/**
 * A query backend of one aggregate type over its index: the four primitives compiled by the 9.x filter, sort,
 * projection and aggregation compilers on the bindings of
 * [ElasticsearchQuerySchemaAdapter][me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter], each hit's
 * `_source` read as [documents] says.
 *
 * A list streams past one page over a point in time ([ElasticsearchQueryPager]); a bounded count or total
 * tracks `limit + 1` hits; a keyset page is one `search_after` window without a point in time. Lists
 * and offset pages end their sort with the documents' tie-breakers, so their order is total. Before the aggregate's
 * first write its index does not exist yet: every read of a missing index answers nothing (no rows, a count of `0`,
 * no groups), as the snapshot store's own load does, rather than failing.
 */
internal class ElasticsearchQueryBackend(
    override val aggregateType: AggregateType,
    private val client: ElasticsearchAsyncClient,
    private val indexName: String,
    private val documents: ElasticsearchQueryDocuments,
    private val batchSize: Int = DEFAULT_SEARCH_BATCH_SIZE,
    keepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE,
) : QueryBackend {
    private val queryPager = ElasticsearchQueryPager(client, indexName, batchSize, keepAlive)
    private val aggregationPager = ElasticsearchAggregationPager(client, indexName, batchSize, keepAlive)

    override val cursorPositions: CursorPositionCodec = ElasticsearchCursorCodec

    override fun stream(query: AdmittedQuery<IListQuery>): Flow<ObjectNode> = flow {
        val listQuery = query.query
        require(listQuery.limit >= 0) { "limit must be greater than or equal to 0." }
        if (listQuery.limit == 0 || listQuery.limit > batchSize) {
            queryPager.search(
                limit = listQuery.limit,
                query = ElasticsearchFilterCompiler.compile(listQuery.filter, query),
                sourceFilter = sourceFilter(listQuery.projection, query),
                sort = totalSort(query) + SHARD_DOC_SORT,
            ).collect { emit(it.record()) }
        } else {
            val request = searchRequest(query, listQuery, totalSort(query)) {
                it.from(0).size(listQuery.limit).trackTotalHits { trackHits -> trackHits.enabled(false) }
            }
            search(request).hits().hits().forEach { emit(it.record()) }
        }
    }.orMissing(query)

    override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage =
        orMissing(query, { emptyPage(window) }) {
            when (window) {
                is PageWindow.Offset -> offsetPage(query, window)
                is PageWindow.Keyset -> keysetPage(query, window)
            }
        }

    private suspend fun offsetPage(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Offset): BackendPage {
        val request = searchRequest(query, query.query, totalSort(query)) {
            it.from(window.offset).size(window.limit).trackTotalHits { trackHits ->
                when {
                    !window.withTotal -> trackHits.enabled(false)
                    window.totalLimit != null -> trackHits.count(window.totalLimit!! + 1)
                    else -> trackHits.enabled(true)
                }
            }
        }
        val hits = search(request).hits()
        val rows = hits.hits().map { it.record() }
        return if (window.withTotal) BackendPage(rows, hits.total()?.value() ?: 0) else BackendPage(rows)
    }

    /** One search_after window, without a point in time; each row's position is its hit's native sort values. */
    private suspend fun keysetPage(query: AdmittedQuery<Queryable<*>>, window: PageWindow.Keyset): BackendPage {
        val queryable = query.query
        val request = searchRequest(query, queryable, ElasticsearchSortCompiler.compileCursor(queryable.sort, query)) {
            it.size(window.limit).trackTotalHits { trackHits -> trackHits.enabled(false) }
            @Suppress("UNCHECKED_CAST")
            window.after?.let { after -> it.searchAfter(after.values as List<FieldValue>) }
            it
        }
        val hits = search(request).hits().hits()
        checkExecution(hits.all { it.sort().size == queryable.sort.size }) {
            "Elasticsearch hit sort values must match the sort."
        }
        return BackendPage(rows = hits.map { it.record() }, positions = hits.map { CursorPosition(it.sort()) })
    }

    override suspend fun count(query: AdmittedQuery<ICountQuery>): Long = orMissing(query, { 0L }) {
        val filter = ElasticsearchFilterCompiler.compile(query.query.filter, query)
        val limit = query.query.limit
        if (limit == null) {
            client.count(CountRequest.of { it.index(indexName).query(filter) }).awaitElasticsearch()
                .requireComplete().count()
        } else {
            // A bounded count stops at `limit + 1` matches: the core reads more as "at least".
            val request = SearchRequest.of {
                it.index(indexName).query(filter).size(0).allowPartialSearchResults(false)
                    .trackTotalHits { trackHits -> trackHits.count(limit + 1) }
            }
            search(request).hits().total()?.value() ?: 0L
        }
    }

    /** A missing index has no groups; without any, the core emits the empty summary, as it does over no records. */
    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
        aggregationPager.execute(ElasticsearchAggregationCompiler.compile(query), window).collect { emit(it) }
    }.orMissing(query)

    /**
     * A search of [queryable] over this index: the invariants every search request carries (the index, no partial
     * results, the compiled filter, [sort] and the source filter), then the caller's window.
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
        sourceFilter(queryable.projection, admitted)?.let { sourceFilter ->
            it.source { source -> source.filter(sourceFilter) }
        }
        window(it)
    }

    /** The projection's source filter, which never fetches the store's own fields. */
    private fun sourceFilter(projection: Projection, admitted: AdmittedQuery<*>): SourceFilter? {
        if (projection.isEmpty()) {
            return if (documents.storeFields.isEmpty()) null else SourceFilter.of { it.excludes(documents.storeFields) }
        }
        val compiled = ElasticsearchProjectionCompiler.compile(projection, admitted)
        if (compiled.includes().isNotEmpty()) return compiled
        return SourceFilter.of { it.excludes(compiled.excludes() + documents.storeFields) }
    }

    /**
     * The sort of a list or an offset page: the query's, then the documents' tie-breakers the query does not name, so
     * a page boundary never splits tied records nondeterministically.
     */
    private fun totalSort(query: AdmittedQuery<Queryable<*>>): List<SortOptions> {
        val sort = ElasticsearchSortCompiler.compile(query.query.sort, query)
        val named = query.query.sort.map { query.physicalPath(it.field) }.toSet()
        return sort + documents.tieBreakers.filterNot(named::contains).map { field ->
            SortOptions.of { it.field { sortField -> sortField.field(field).order(SortOrder.Asc) } }
        }
    }

    private suspend fun search(request: SearchRequest) =
        client.search(request, ObjectNode::class.java).awaitElasticsearch().requireComplete()

    private fun Hit<ObjectNode>.record(): ObjectNode =
        documents.record(source() ?: throw QueryExecutionException("Elasticsearch hit is missing _source."))

    private fun emptyPage(window: PageWindow): BackendPage = when (window) {
        is PageWindow.Offset -> if (window.withTotal) BackendPage(emptyList(), 0) else BackendPage(emptyList())
        is PageWindow.Keyset -> BackendPage(emptyList(), positions = emptyList())
    }

    /**
     * [missing] when the index does not exist. A query admitted against the provisional schema of a missing index
     * that finds the index (created since, by the first write) is refused: its bindings for the paths the index did
     * not map are the logical model's, not the index's. The provisional schema is never cached.
     */
    private suspend fun <T> orMissing(query: AdmittedQuery<*>, missing: () -> T, block: suspend () -> T): T {
        val result = try {
            block()
        } catch (failure: ElasticsearchException) {
            if (ElasticsearchFailures.isIndexNotFound(failure)) return missing()
            throw failure
        }
        if (query.provisional) throw createdSinceAdmission()
        return result
    }

    private fun Flow<ObjectNode>.orMissing(query: AdmittedQuery<*>): Flow<ObjectNode> = flow {
        try {
            collect { emit(it) }
        } catch (failure: ElasticsearchException) {
            if (ElasticsearchFailures.isIndexNotFound(failure)) return@flow
            throw failure
        }
        if (query.provisional) throw createdSinceAdmission()
    }

    private fun createdSinceAdmission(): Throwable = QuerySchemaUnavailableException(
        "Elasticsearch index [$indexName] was created after the query was admitted against its provisional schema; " +
            "retry once its query schema follows the index.",
    )

    private companion object {
        /** The tie-breaker of a stream over a point in time: each shard's document order. */
        val SHARD_DOC_SORT: SortOptions = SortOptions.of {
            it.field { field -> field.field("_shard_doc").order(SortOrder.Asc) }
        }
    }
}

/** Converts an aggregation row built from response values; the core checks that it is standard JSON. */
internal fun Map<*, *>.toObjectNode(): ObjectNode = WowJson.mapper.valueToTree(this)
