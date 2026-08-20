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
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration

class ElasticsearchQueryPagerTest {
    private val elasticsearchClient = mockk<ReactiveElasticsearchClient>()
    private val query = matchAll { it }
    private val sort = listOf(
        SortOptions.of { it.field { field -> field.field("sequence").order(SortOrder.Asc) } },
        SortOptions.of { it.field { field -> field.field("_shard_doc").order(SortOrder.Asc) } },
    )

    @Test
    fun `should read every page in stable order and close latest pit`() {
        val openRequest = slot<OpenPointInTimeRequest>()
        val searchRequests = mutableListOf<SearchRequest>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        stubPointInTime(openRequest, closeRequest)
        every { elasticsearchClient.search(capture(searchRequests), Map::class.java) } returnsMany listOf(
            Mono.just(searchResponse("pit-2", hit("1", 1), hit("2", 2))),
            Mono.just(searchResponse("pit-3", hit("3", 3))),
        )

        ElasticsearchQueryPager(
            elasticsearchClient,
            "test-index",
            batchSize = 2,
            keepAlive = Duration.ofMinutes(5),
        )
            .search(0, query, null, sort)
            .mapNotNull { it.id() }
            .test()
            .expectNext("1", "2", "3")
            .verifyComplete()

        openRequest.captured.index().assert().containsExactly("test-index")
        openRequest.captured.keepAlive().time().assert().isEqualTo("5m")
        searchRequests.assert().hasSize(2)
        searchRequests[0].index().assert().isEmpty()
        searchRequests[0].size().assert().isEqualTo(2)
        searchRequests[0].searchAfter().assert().isEmpty()
        searchRequests[0].pit()!!.keepAlive()!!.time().assert().isEqualTo("5m")
        searchRequests[0].sort().map { it.field().field() }.assert().containsExactly("sequence", "_shard_doc")
        searchRequests[1].pit()!!.id().assert().isEqualTo("pit-2")
        searchRequests[1].searchAfter().map { it.longValue() }.assert().containsExactly(2L, 102L)
        closeRequest.captured.id().assert().isEqualTo("pit-3")
    }

    @Test
    fun `should stop at positive limit`() {
        val searchRequests = mutableListOf<SearchRequest>()
        stubPointInTime()
        every { elasticsearchClient.search(capture(searchRequests), Map::class.java) } returnsMany listOf(
            Mono.just(searchResponse("pit-2", hit("1", 1), hit("2", 2))),
            Mono.just(searchResponse("pit-3", hit("3", 3))),
        )

        ElasticsearchQueryPager(elasticsearchClient, "test-index", batchSize = 2)
            .search(3, query, null, sort)
            .test()
            .expectNextCount(3)
            .verifyComplete()

        searchRequests.map { it.size() }.assert().containsExactly(2, 1)
        verify(exactly = 2) { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) }
    }

    @Test
    fun `should close latest pit when an intermediate page fails`() {
        val closeRequest = slot<ClosePointInTimeRequest>()
        stubPointInTime(closeRequest = closeRequest)
        every { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(searchResponse("pit-2", hit("1", 1), hit("2", 2))),
            Mono.error(IllegalStateException("page failed")),
        )

        ElasticsearchQueryPager(elasticsearchClient, "test-index", batchSize = 2)
            .search(0, query, null, sort)
            .test()
            .expectNextCount(2)
            .expectErrorMessage("page failed")
            .verify()

        closeRequest.captured.id().assert().isEqualTo("pit-2")
    }

    @Test
    fun `should close latest pit when cancelled`() {
        val closeRequest = slot<ClosePointInTimeRequest>()
        stubPointInTime(closeRequest = closeRequest)
        every { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
            Mono.just(searchResponse("pit-2", hit("1", 1), hit("2", 2))),
            Mono.never(),
        )

        ElasticsearchQueryPager(elasticsearchClient, "test-index", batchSize = 2)
            .search(0, query, null, sort)
            .test()
            .expectNextCount(1)
            .thenCancel()
            .verify()

        closeRequest.captured.id().assert().isEqualTo("pit-2")
    }

    @Test
    fun `close failure should not fail a completed query`() {
        every { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(
            openPointInTimeResponse()
        )
        every { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            searchResponse("pit-2", hit("1", 1))
        )
        every { elasticsearchClient.closePointInTime(any<ClosePointInTimeRequest>()) } returns Mono.error(
            IllegalStateException("close failed")
        )

        ElasticsearchQueryPager(elasticsearchClient, "test-index", batchSize = 2)
            .search(0, query, null, sort)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    private fun stubPointInTime(
        openRequest: io.mockk.CapturingSlot<OpenPointInTimeRequest>? = null,
        closeRequest: io.mockk.CapturingSlot<ClosePointInTimeRequest>? = null,
    ) {
        if (openRequest == null) {
            every { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(
                openPointInTimeResponse()
            )
        } else {
            every { elasticsearchClient.openPointInTime(capture(openRequest)) } returns Mono.just(
                openPointInTimeResponse()
            )
        }
        if (closeRequest == null) {
            every { elasticsearchClient.closePointInTime(any<ClosePointInTimeRequest>()) } returns Mono.just(
                closePointInTimeResponse()
            )
        } else {
            every { elasticsearchClient.closePointInTime(capture(closeRequest)) } returns Mono.just(
                closePointInTimeResponse()
            )
        }
    }

    private fun openPointInTimeResponse(): OpenPointInTimeResponse {
        return OpenPointInTimeResponse.of {
            it.id("pit-1")
                .shards { shards -> shards.failed(0).successful(1).total(1) }
        }
    }

    private fun closePointInTimeResponse(): ClosePointInTimeResponse {
        return ClosePointInTimeResponse.of { it.succeeded(true).numFreed(1) }
    }

    private fun hit(id: String, sequence: Long): Pair<String, List<FieldValue>> {
        return id to listOf(FieldValue.of(sequence), FieldValue.of(sequence + 100))
    }

    private fun searchResponse(
        pitId: String,
        vararg hits: Pair<String, List<FieldValue>>,
    ): SearchResponse<Map<*, *>> {
        return SearchResponse.of<Map<*, *>> {
            it.took(1)
                .timedOut(false)
                .pitId(pitId)
                .shards { shards -> shards.failed(0).successful(1).total(1) }
                .hits { metadata ->
                    hits.forEach { (id, sort) ->
                        metadata.hits { hit ->
                            hit.index("test-index")
                                .id(id)
                                .source(mutableMapOf<String, Any?>("id" to id))
                                .sort(sort)
                        }
                    }
                    metadata
                }
        }
    }
}
