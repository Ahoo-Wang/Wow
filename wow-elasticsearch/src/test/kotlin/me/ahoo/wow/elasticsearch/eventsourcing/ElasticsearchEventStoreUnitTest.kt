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

package me.ahoo.wow.elasticsearch.eventsourcing

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import co.elastic.clients.util.ObjectBuilder
import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.function.Function

class ElasticsearchEventStoreUnitTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val aggregateId: AggregateId = MaterializedNamedAggregate("test", "aggregate").aggregateId("id")

    @Test
    fun `batch size must be positive`() {
        assertThrows<IllegalArgumentException> {
            ElasticsearchEventStore(client, batchSize = 0)
        }
    }

    @Test
    fun `non 404 elasticsearch error should propagate`() {
        val failure = mockk<ElasticsearchException> {
            every { status() } returns 500
        }
        stubSearch(Mono.error(failure))

        ElasticsearchEventStore(client).load(aggregateId)
            .test()
            .expectErrorMatches { it === failure }
            .verify()
    }

    @Test
    fun `non elasticsearch error should propagate`() {
        val failure = IllegalStateException("search failed")
        stubSearch(Mono.error(failure))

        ElasticsearchEventStore(client).load(aggregateId)
            .test()
            .expectErrorMatches { it === failure }
            .verify()
    }

    @Test
    fun `event stream hit source is required`() {
        stubSearch(
            Mono.just(searchResponse(source = null, sort = listOf(FieldValue.of(1), FieldValue.of("id")))),
        )

        ElasticsearchEventStore(client).load(aggregateId)
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `full page requires search after cursor`() {
        stubSearch(
            Mono.just(searchResponse(source = mockk(), sort = emptyList())),
        )

        ElasticsearchEventStore(client, batchSize = 1).load(aggregateId)
            .test()
            .expectErrorMatches {
                it is IllegalStateException &&
                    it.message == "Elasticsearch search_after cursor must not be empty."
            }
            .verify()
    }

    private fun stubSearch(response: Mono<ResponseBody<DomainEventStream>>) {
        every {
            client.search(
                any<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>(),
                DomainEventStream::class.java,
            )
        } returns response
    }

    private fun searchResponse(
        source: DomainEventStream?,
        sort: List<FieldValue>,
    ): SearchResponse<DomainEventStream> {
        return SearchResponse.of<DomainEventStream> { response ->
            response.took(1)
                .timedOut(false)
                .shards { shards -> shards.failed(0).successful(1).total(1) }
                .hits { hits ->
                    hits.hits { hit ->
                        hit.index("test-index")
                            .id("id")
                            .sort(sort)
                        source?.let(hit::source)
                        hit
                    }
                }
        }
    }
}
