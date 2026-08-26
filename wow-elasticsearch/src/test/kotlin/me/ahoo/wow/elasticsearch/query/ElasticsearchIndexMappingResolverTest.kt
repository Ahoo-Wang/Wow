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

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test

class ElasticsearchIndexMappingResolverTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val indicesClient = mockk<ReactiveElasticsearchIndicesClient>()

    init {
        every { client.indices() } returns indicesClient
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

    private fun mappingResponse(field: String): GetMappingResponse = GetMappingResponse.of { response ->
        response.mappings(INDEX, IndexMappingRecord.of { record -> record.mappings(mapping(field)) })
    }

    private fun mapping(field: String): TypeMapping = TypeMapping.of { mapping ->
        mapping.properties(field) { it.keyword { keyword -> keyword } }
    }

    companion object {
        private const val INDEX = "wow.catalog.sku.snapshot"
    }
}
