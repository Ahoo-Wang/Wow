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
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.util.NamedValue
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

internal class ElasticsearchAggregationPager(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    indexName: String,
    private val batchSize: Int = DEFAULT_SEARCH_BATCH_SIZE,
    keepAlive: Duration = DEFAULT_PIT_KEEP_ALIVE,
) {
    private val pointInTime = ElasticsearchPointInTime(elasticsearchClient, indexName, keepAlive)

    init {
        require(batchSize in 1..DEFAULT_SEARCH_BATCH_SIZE) {
            "batchSize must be between 1 and $DEFAULT_SEARCH_BATCH_SIZE."
        }
    }

    fun search(
        query: Query,
        sources: List<NamedValue<CompositeAggregationSource>>,
        metrics: Map<String, Aggregation>,
        limit: Int = 0,
    ): Flux<CompositeBucket> {
        require(sources.isNotEmpty()) { "aggregation sources must not be empty." }
        require(limit >= 0) { "limit must be greater than or equal to 0." }
        return pointInTime.use { pit ->
            searchPage(pit, query, sources, metrics, limit)
                .expand { page ->
                    page.afterKey?.let { afterKey ->
                        searchPage(pit, query, sources, metrics, limit, page.fetched, afterKey)
                    } ?: Mono.empty()
                }
                .concatMapIterable({ it.buckets }, 1)
        }
    }

    private fun searchPage(
        pit: ElasticsearchPointInTime.Session,
        query: Query,
        sources: List<NamedValue<CompositeAggregationSource>>,
        metrics: Map<String, Aggregation>,
        limit: Int,
        fetched: Long = 0,
        afterKey: Map<String, FieldValue> = emptyMap(),
    ): Mono<AggregationPage> {
        val pageSize = if (limit == 0) {
            batchSize
        } else {
            minOf(batchSize.toLong(), limit.toLong() - fetched).toInt()
        }
        return Mono.defer {
            val rootAggregation = Aggregation.of { aggregation ->
                if (metrics.isNotEmpty()) {
                    aggregation.aggregations(metrics)
                }
                aggregation.composite { composite ->
                    composite.size(pageSize).sources(sources)
                    if (afterKey.isNotEmpty()) {
                        composite.after(afterKey)
                    }
                    composite
                }
            }
            val request = SearchRequest.of {
                it.size(0)
                    .trackTotalHits { trackTotalHits -> trackTotalHits.enabled(false) }
                    .query(query)
                    .pit { pointInTime ->
                        pointInTime.id(pit.id)
                            .keepAlive { keepAlive -> keepAlive.time(this.pointInTime.keepAliveValue) }
                    }
                    .aggregations(ROOT_AGGREGATION, rootAggregation)
            }
            elasticsearchClient.search(request, Map::class.java)
        }.map { response ->
            response.pitId()?.takeIf(String::isNotBlank)?.let { pit.id = it }
            val aggregate = checkNotNull(response.aggregations()[ROOT_AGGREGATION]) {
                "Elasticsearch aggregation response is missing [$ROOT_AGGREGATION]."
            }
            check(aggregate.isComposite) {
                "Elasticsearch aggregation response [$ROOT_AGGREGATION] must be composite."
            }
            val composite = aggregate.composite()
            check(composite.buckets().isArray) {
                "Elasticsearch composite aggregation must return array buckets."
            }
            val buckets = composite.buckets().array()
            val totalFetched = fetched + buckets.size
            val hasNextPage = buckets.size == pageSize && (limit == 0 || totalFetched < limit.toLong())
            AggregationPage(
                buckets = buckets,
                fetched = totalFetched,
                afterKey = composite.afterKey().takeIf { hasNextPage && it.isNotEmpty() },
            )
        }
    }

    private data class AggregationPage(
        val buckets: List<CompositeBucket>,
        val fetched: Long,
        val afterKey: Map<String, FieldValue>?,
    )

    private companion object {
        const val ROOT_AGGREGATION = "__wow_aggregation__"
    }
}
