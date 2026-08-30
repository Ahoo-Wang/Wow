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
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.SourceFilter
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import kotlin.math.min

internal const val DEFAULT_SEARCH_BATCH_SIZE = 10_000
internal val DEFAULT_PIT_KEEP_ALIVE: Duration = Duration.ofMinutes(1)

internal class ElasticsearchQueryPager(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val indexName: String,
    private val batchSize: Int = DEFAULT_SEARCH_BATCH_SIZE,
    private val keepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE,
) {
    private val pointInTime = ElasticsearchPointInTime(elasticsearchClient, indexName, keepAlive)

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
    ): Flux<Hit<ObjectNode>> {
        require(limit >= 0) { "limit must be greater than or equal to 0." }
        require(sort.isNotEmpty()) { "sort must not be empty when using search_after." }
        return pointInTime.use { pit ->
            searchPage(pit, limit, query, sourceFilter, sort)
                .expand { page ->
                    page.nextSearchAfter?.let {
                        searchPage(pit, limit, query, sourceFilter, sort, page.fetched, it)
                    } ?: Mono.empty()
                }
                .concatMapIterable({ it.hits }, 1)
        }
    }

    private fun searchPage(
        pit: ElasticsearchPointInTime.Session,
        limit: Int,
        query: Query,
        sourceFilter: SourceFilter?,
        sort: List<SortOptions>,
        fetched: Long = 0,
        searchAfter: List<FieldValue> = emptyList(),
    ): Mono<SearchPage> {
        val pageSize = if (limit == 0) {
            batchSize
        } else {
            min(batchSize.toLong(), limit.toLong() - fetched).toInt()
        }
        return Mono.defer {
            val request = SearchRequest.of {
                it.query(query)
                    .size(pageSize)
                    .trackTotalHits { trackHits -> trackHits.enabled(false) }
                    .pit { pitBuilder ->
                        pitBuilder.id(pit.id)
                            .keepAlive { it.time(pointInTime.keepAliveValue) }
                    }
                    .sort(sort)
                if (searchAfter.isNotEmpty()) {
                    it.searchAfter(searchAfter)
                }
                if (sourceFilter != null) {
                    it.source { source -> source.filter(sourceFilter) }
                }
                it
            }
            elasticsearchClient.search(request, ObjectNode::class.java)
        }.map { response ->
            pit.update(response.pitId())
            val hits = response.hits().hits()
            val totalFetched = fetched + hits.size
            val hasNextPage = hits.size == pageSize && (limit == 0 || totalFetched < limit.toLong())
            val nextSearchAfter = if (hasNextPage) {
                hits.last().sort().also {
                    check(it.isNotEmpty()) { "Elasticsearch search_after cursor must not be empty." }
                }
            } else {
                null
            }
            SearchPage(hits, totalFetched, nextSearchAfter)
        }
    }

    private data class SearchPage(
        val hits: List<Hit<ObjectNode>>,
        val fetched: Long,
        val nextSearchAfter: List<FieldValue>?,
    )
}
