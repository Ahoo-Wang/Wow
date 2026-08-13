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

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.error.QueryException
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class ReactiveClientElasticsearchQueryTransportTest {
    @Test
    fun `same response validation failures close its rotated pit id`() {
        listOf(::timedOut, ::shardFailed, ::malformedSource).forEach { invalidResponse ->
            val client = mockk<ReactiveElasticsearchClient>()
            val closeRequest = slot<ClosePointInTimeRequest>()
            every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(
                OpenPointInTimeResponse.of { response ->
                    response.id("pit-0").shards { shards -> shards.failed(0).successful(1).total(1) }
                },
            )
            every { client.search(any<SearchRequest>(), Map::class.java) } returnsMany listOf(
                Mono.just(validPage("pit-1")),
                Mono.just(invalidResponse("pit-2")),
            )
            every { client.closePointInTime(capture(closeRequest)) } returns Mono.just(
                ClosePointInTimeResponse.of { response -> response.succeeded(true).numFreed(1) },
            )
            val executor = PitSearchAfterExecutor(
                ReactiveClientElasticsearchQueryTransport(client),
                "index",
                1,
            ) { request -> request }

            StepVerifier.create(executor.execute(maxResults = null))
                .expectNextCount(1)
                .expectError(QueryException::class.java)
                .verify()

            closeRequest.captured.id().assert().isEqualTo("pit-2")
        }
    }

    private fun validPage(pitId: String): SearchResponse<Map<*, *>> = response(pitId) { hits ->
        hits.hits { hit ->
            hit.index("index")
                .id("id")
                .source(mapOf("logicalId" to "id"))
                .sort(FieldValue.of(1L))
        }
    }

    private fun timedOut(pitId: String): SearchResponse<Map<*, *>> = response(pitId, timedOut = true)

    private fun shardFailed(pitId: String): SearchResponse<Map<*, *>> = response(pitId, failedShards = 1)

    private fun malformedSource(pitId: String): SearchResponse<Map<*, *>> = response(pitId) { hits ->
        hits.hits { hit -> hit.index("index").id("id").sort(FieldValue.of(2L)) }
    }

    private fun response(
        pitId: String,
        timedOut: Boolean = false,
        failedShards: Int = 0,
        configureHits: (co.elastic.clients.elasticsearch.core.search.HitsMetadata.Builder<Map<*, *>>) -> Unit = {},
    ): SearchResponse<Map<*, *>> = SearchResponse.of<Map<*, *>> { response ->
        response.took(1)
            .timedOut(timedOut)
            .pitId(pitId)
            .shards { shards -> shards.failed(failedShards).successful(1).total(1 + failedShards) }
            .hits { hits ->
                hits.hits(emptyList())
                configureHits(hits)
                hits
            }
    }
}
