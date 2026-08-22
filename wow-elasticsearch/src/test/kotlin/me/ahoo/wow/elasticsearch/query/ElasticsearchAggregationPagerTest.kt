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
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregate
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.util.NamedValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ElasticsearchAggregationPagerTest {
    private val elasticsearchClient = mockk<ReactiveElasticsearchClient>()
    private val source = NamedValue.of(
        "group",
        CompositeAggregationSource.of { it.terms { terms -> terms.field("group") } },
    )

    @Test
    fun `should page composite buckets and close latest pit`() {
        val searchRequests = mutableListOf<SearchRequest>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        stubPointInTime(closeRequest)
        every { elasticsearchClient.search(capture(searchRequests), Map::class.java) } returnsMany listOf(
            Mono.just(aggregationResponse("pit-2", listOf(bucket(1), bucket(2)), FieldValue.of(2L))),
            Mono.just(aggregationResponse("pit-3", listOf(bucket(3)))),
        )

        ElasticsearchAggregationPager(elasticsearchClient, "test-index", batchSize = 2)
            .search(matchAll { it }, listOf(source), emptyMap())
            .map { it.key()["group"]!!.longValue() }
            .test()
            .expectNext(1L, 2L, 3L)
            .verifyComplete()

        searchRequests.assert().hasSize(2)
        searchRequests.forEach { it.allowPartialSearchResults().assert().isFalse() }
        searchRequests[0].aggregations()[ROOT]!!.composite().after().assert().isEmpty()
        searchRequests[1].aggregations()[ROOT]!!.composite().after()["group"]!!.longValue().assert().isEqualTo(2L)
        closeRequest.captured.id().assert().isEqualTo("pit-3")
    }

    @Test
    fun `should close pit when composite paging is cancelled`() {
        val closeRequest = slot<ClosePointInTimeRequest>()
        stubPointInTime(closeRequest)
        every { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(aggregationResponse("pit-2", listOf(bucket(1), bucket(2)), FieldValue.of(2L))),
            Mono.never(),
        )

        ElasticsearchAggregationPager(elasticsearchClient, "test-index", batchSize = 2)
            .search(matchAll { it }, listOf(source), emptyMap())
            .test()
            .expectNextCount(1)
            .thenCancel()
            .verify()

        closeRequest.captured.id().assert().isEqualTo("pit-2")
    }

    @Test
    fun `should reject failed shard responses`() {
        val closeRequest = slot<ClosePointInTimeRequest>()
        stubPointInTime(closeRequest)
        every { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            aggregationResponse("pit-2", emptyList(), failedShards = 1),
        )

        ElasticsearchAggregationPager(elasticsearchClient, "test-index", batchSize = 2)
            .search(matchAll { it }, listOf(source), emptyMap())
            .test()
            .expectErrorMessage("Elasticsearch aggregation search failed on 1 shard(s).")
            .verify()

        closeRequest.captured.id().assert().isEqualTo("pit-2")
    }

    private fun stubPointInTime(closeRequest: io.mockk.CapturingSlot<ClosePointInTimeRequest>) {
        every { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(
            OpenPointInTimeResponse.of {
                it.id("pit-1").shards { shards -> shards.failed(0).successful(1).total(1) }
            }
        )
        every { elasticsearchClient.closePointInTime(capture(closeRequest)) } returns Mono.just(
            ClosePointInTimeResponse.of { it.succeeded(true).numFreed(1) }
        )
    }

    private fun bucket(key: Long): CompositeBucket = CompositeBucket.of {
        it.key("group", key).docCount(1)
    }

    private fun aggregationResponse(
        pitId: String,
        buckets: List<CompositeBucket>,
        afterKey: FieldValue? = null,
        failedShards: Int = 0,
    ): SearchResponse<Map<*, *>> = SearchResponse.of {
        it.took(1)
            .timedOut(false)
            .shards { shards -> shards.failed(failedShards).successful(1).total(1 + failedShards) }
            .hits { hits -> hits.hits(emptyList()) }
            .aggregations(
                ROOT,
                Aggregate(
                    CompositeAggregate.of { composite ->
                        composite.buckets { bucketContainer -> bucketContainer.array(buckets) }
                        if (afterKey != null) {
                            composite.afterKey("group", afterKey)
                        }
                        composite
                    }
                ),
            ).pitId(pitId)
    }

    private companion object {
        const val ROOT = "__wow_aggregation__"
    }
}
