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
import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.SourceFilter
import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.elasticsearch.awaitElasticsearch
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import kotlin.math.min

internal const val DEFAULT_SEARCH_BATCH_SIZE = 10_000
internal val DEFAULT_PIT_KEEP_ALIVE: Duration = Duration.ofMinutes(1)

/**
 * Streams the hits of a search past one page: pages of [batchSize] over a point in time, each after the last hit's
 * sort values (`search_after`), until [search]'s limit or the last hit.
 */
internal class ElasticsearchQueryPager(
    private val client: ElasticsearchAsyncClient,
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

    fun search(
        limit: Int,
        query: Query,
        sourceFilter: SourceFilter?,
        sort: List<SortOptions>,
    ): Flow<Hit<ObjectNode>> {
        require(limit >= 0) { "limit must be greater than or equal to 0." }
        require(sort.isNotEmpty()) { "sort must not be empty when using search_after." }
        return pointInTime.use { pit ->
            var fetched = 0L
            var searchAfter: List<FieldValue> = emptyList()
            while (true) {
                val pageSize = if (limit == 0) batchSize else min(batchSize.toLong(), limit - fetched).toInt()
                val request = SearchRequest.of {
                    it.query(query)
                        .size(pageSize)
                        .allowPartialSearchResults(false)
                        .trackTotalHits { trackHits -> trackHits.enabled(false) }
                        .pit { pitBuilder -> pitBuilder.id(pit.id).keepAlive { time -> time.time(pointInTime.keepAliveValue) } }
                        .sort(sort)
                    if (searchAfter.isNotEmpty()) it.searchAfter(searchAfter)
                    if (sourceFilter != null) it.source { source -> source.filter(sourceFilter) }
                    it
                }
                val response = client.search(request, ObjectNode::class.java).awaitElasticsearch()
                pit.update(response.pitId())
                response.requireComplete()
                val hits = response.hits().hits()
                hits.forEach { emit(it) }
                fetched += hits.size
                val hasNextPage = hits.size == pageSize && (limit == 0 || fetched < limit.toLong())
                if (!hasNextPage) break
                searchAfter = hits.last().sort().also {
                    checkExecution(it.isNotEmpty()) { "Elasticsearch search_after cursor must not be empty." }
                }
            }
        }
    }
}
