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

import co.elastic.clients.elasticsearch._types.ErrorResponse
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping
import co.elastic.clients.elasticsearch._types.mapping.DynamicTemplate
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import co.elastic.clients.transport.ElasticsearchTransport
import co.elastic.clients.transport.TransportOptions
import co.elastic.clients.util.NamedValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.json.JsonValue
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.util.concurrent.CompletableFuture

class ElasticsearchIndexMappingResolverTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val indicesClient = mockk<ReactiveElasticsearchIndicesClient>()

    init {
        every { client.indices() } returns indicesClient
    }

    @Test
    fun `immediate refresh after success should issue a new mapping request`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(mappingResponse(field = "name")),
            Mono.just(mappingResponse(field = "code")),
        )
        val resolver = ElasticsearchIndexMappingResolver(client)

        resolver.refresh(INDEX)
            .flatMap { first -> resolver.refresh(INDEX).map { second -> first to second } }
            .test()
            .assertNext { (first, second) ->
                first.fields.assert().containsKey("name")
                second.fields.assert().containsKey("code")
            }.verifyComplete()

        verify(exactly = 2) { indicesClient.getMapping(any<GetMappingRequest>()) }
    }

    @Test
    fun `immediate retry after failure should issue a new mapping request`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.error(IllegalStateException("unavailable")),
            Mono.just(mappingResponse(field = "code")),
        )
        val resolver = ElasticsearchIndexMappingResolver(client)

        resolver.refresh(INDEX)
            .onErrorResume { resolver.refresh(INDEX) }
            .test()
            .assertNext { mapping -> mapping.fields.assert().containsKey("code") }
            .verifyComplete()

        verify(exactly = 2) { indicesClient.getMapping(any<GetMappingRequest>()) }
    }

    @Test
    fun `should cache successful mapping and actively refresh it`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(mappingResponse(field = "name")),
            Mono.just(mappingResponse(field = "code")),
        )
        val resolver = ElasticsearchIndexMappingResolver(client)

        resolver.currentOrLoad(INDEX).block()!!.fields.assert().containsKey("name")
        resolver.currentOrLoad(INDEX).block()!!.fields.assert().containsKey("name")
        resolver.refresh(INDEX).block()!!.fields.assert().containsKey("code")
        resolver.currentOrLoad(INDEX).block()!!.fields.assert().containsKey("code")

        verify(exactly = 2) { indicesClient.getMapping(any<GetMappingRequest>()) }
    }

    @Test
    fun `failed refresh should keep previous mapping and retry later`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(mappingResponse(field = "name")),
            Mono.error(IllegalStateException("unavailable")),
            Mono.just(mappingResponse(field = "code")),
        )
        val resolver = ElasticsearchIndexMappingResolver(client)
        resolver.currentOrLoad(INDEX).block()

        resolver.refresh(INDEX).test().expectErrorMessage("unavailable").verify()
        resolver.currentOrLoad(INDEX).block()!!.fields.assert().containsKey("name")
        resolver.refresh(INDEX).block()!!.fields.assert().containsKey("code")
    }

    @Test
    fun `concurrent initial loads should share one mapping request`() {
        val response = Sinks.one<GetMappingResponse>()
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns response.asMono()
        val resolver = ElasticsearchIndexMappingResolver(client)

        val verifier = Mono.zip(resolver.currentOrLoad(INDEX), resolver.currentOrLoad(INDEX)).test()
        response.tryEmitValue(mappingResponse(field = "name"))

        verifier.expectNextCount(1).verifyComplete()
        verify(exactly = 1) { indicesClient.getMapping(any<GetMappingRequest>()) }
    }

    @Test
    fun `multiple physical indices should fail closed`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            GetMappingResponse.of { response ->
                response.mappings(
                    "$INDEX-000001",
                    IndexMappingRecord.of { record -> record.mappings(mapping("name")) },
                ).mappings(
                    "$INDEX-000002",
                    IndexMappingRecord.of { record -> record.mappings(mapping("name")) },
                )
            },
        )

        ElasticsearchIndexMappingResolver(client).currentOrLoad(INDEX).test()
            .expectErrorMatches {
                it.message!!.startsWith("Elasticsearch index [$INDEX] must resolve to exactly one physical index")
            }.verify()
    }

    @Test
    fun `raw mapping failure should preserve its cause and allow retry`() {
        val transport = mockk<ElasticsearchTransport>()
        val options = mockk<TransportOptions>()
        val failure = IllegalStateException("raw mapping unavailable")
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(dynamicMappingResponse()),
            Mono.just(dynamicMappingResponse()),
        )
        every { client._transport() } returns transport
        every { client._transportOptions() } returns options
        every {
            transport.performRequestAsync<GetMappingRequest, JsonValue, ErrorResponse>(any(), any(), options)
        } returnsMany listOf(
            CompletableFuture.failedFuture(failure),
            CompletableFuture.completedFuture(rawDynamicMapping()),
        )
        val resolver = ElasticsearchIndexMappingResolver(client)

        resolver.refresh(INDEX).test().expectErrorMatches { it === failure }.verify()
        resolver.refresh(INDEX).test().expectNextCount(1).verifyComplete()
    }

    private fun mappingResponse(field: String): GetMappingResponse = GetMappingResponse.of { response ->
        response.mappings(INDEX, IndexMappingRecord.of { record -> record.mappings(mapping(field)) })
    }

    private fun mapping(field: String): TypeMapping = TypeMapping.of { mapping ->
        mapping.properties(field) { it.keyword { keyword -> keyword } }
    }

    private fun dynamicMappingResponse(): GetMappingResponse = GetMappingResponse.of { response ->
        response.mappings(
            INDEX,
            IndexMappingRecord.of { record ->
                record.mappings { mapping ->
                    mapping.dateDetection(false)
                        .properties("tags") {
                            it.`object` { objectField -> objectField.dynamic(DynamicMapping.True) }
                        }.dynamicTemplates(
                            NamedValue.of(
                                "tags_keyword",
                                DynamicTemplate.of { template ->
                                    template.matchMappingType("string")
                                        .pathMatch("tags.*")
                                        .mapping { it.keyword { keyword -> keyword } }
                                },
                            ),
                        )
                }
            },
        )
    }

    private fun rawDynamicMapping(): JsonValue = jakarta.json.Json.createReader(
        """
        {
          "$INDEX": {
            "mappings": {
              "date_detection": false,
              "properties": {"tags": {"type": "object", "dynamic": true}},
              "dynamic_templates": [
                {
                  "tags_keyword": {
                    "match_mapping_type": "string",
                    "path_match": "tags.*",
                    "mapping": {"type": "keyword"}
                  }
                }
              ]
            }
          }
        }
        """.trimIndent().reader(),
    ).readValue()

    companion object {
        private const val INDEX = "wow.catalog.sku.snapshot"
    }
}
