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

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.ErrorResponse
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.elasticsearch.indices.SimulateIndexTemplateRequest
import co.elastic.clients.elasticsearch.indices.SimulateIndexTemplateResponse
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import co.elastic.clients.transport.ElasticsearchTransport
import co.elastic.clients.transport.Endpoint
import co.elastic.clients.transport.TransportOptions
import co.elastic.clients.util.MissingRequiredPropertyException
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
import java.util.concurrent.CompletableFuture

private typealias SimulateEndpoint = Endpoint<SimulateIndexTemplateRequest, SimulateIndexTemplateResponse, *>

class ElasticsearchIndexMappingResolverTest {
    private val client = mockk<ReactiveElasticsearchClient>()
    private val indicesClient = mockk<ReactiveElasticsearchIndicesClient>()

    private val transport = mockk<ElasticsearchTransport>()

    init {
        every { client.indices() } returns indicesClient
        every { client._transport() } returns transport
        every { client._transportOptions() } returns mockk<TransportOptions>()
        every { indicesClient.getSettings(any<GetIndicesSettingsRequest>()) } returns Mono.just(indexSettingsResponse())
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
    fun `the index's max_result_window is loaded with its mapping`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(mappingResponse(field = "name"))
        val resolver = ElasticsearchIndexMappingResolver(client)

        resolver.refresh(INDEX).block()!!.maxResultWindow.assert().isEqualTo(DEFAULT_MAX_RESULT_WINDOW)

        every { indicesClient.getSettings(any<GetIndicesSettingsRequest>()) } returns Mono.just(
            indexSettingsResponse(maxResultWindow = 20),
        )
        resolver.refresh(INDEX).block()!!.maxResultWindow.assert().isEqualTo(20)
    }

    @Test
    fun `a missing index has the mapping its templates will create it with, and is read again on the next load`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.error(indexNotFound()),
            Mono.just(mappingResponse(field = "code")),
        )
        simulation(
            CompletableFuture.completedFuture(
                SimulateIndexTemplateResponse.of { response ->
                    response.template { template ->
                        template.mappings(mapping("name"))
                            .settings { settings -> settings.index { index -> index.maxResultWindow(50) } }
                            .aliases(emptyMap())
                    }.overlapping(emptyList())
                },
            )
        )
        val resolver = ElasticsearchIndexMappingResolver(client)

        val provisional = resolver.currentOrLoad(INDEX).block()!!
        provisional.fields.assert().containsOnlyKeys("name")
        provisional.maxResultWindow.assert().isEqualTo(50)
        provisional.provisional.assert().isTrue()
        resolver.currentOrLoad(INDEX).block()!!.fields.assert().containsOnlyKeys("code")
        resolver.currentOrLoad(INDEX).block()!!.fields.assert().containsOnlyKeys("code")

        verify(exactly = 2) { indicesClient.getMapping(any<GetMappingRequest>()) }
        verify(exactly = 1) {
            transport.performRequestAsync(any<SimulateIndexTemplateRequest>(), any<SimulateEndpoint>(), any())
        }
    }

    @Test
    fun `a missing index no template matches maps no field`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.error(indexNotFound())
        simulation(
            CompletableFuture.failedFuture(
                RuntimeException(
                    MissingRequiredPropertyException(SimulateIndexTemplateResponse::class.java, "template")
                ),
            )
        )

        val mapping = ElasticsearchIndexMappingResolver(client).currentOrLoad(INDEX).block()!!

        mapping.fields.assert().isEmpty()
        mapping.maxResultWindow.assert().isEqualTo(DEFAULT_MAX_RESULT_WINDOW)
        mapping.provisional.assert().isTrue()
    }

    @Test
    fun `the client reads the empty simulation of no matching template as a missing template`() {
        // Elasticsearch answers `{}` when no template matches; the resolver recognizes the client's failure to read it.
        val mapper = me.ahoo.wow.elasticsearch.WowJsonpMapper
        val parser = mapper.jsonProvider().createParser(java.io.StringReader("{}"))
        val failure = org.junit.jupiter.api.assertThrows<MissingRequiredPropertyException> {
            SimulateIndexTemplateResponse._DESERIALIZER.deserialize(parser, mapper)
        }
        failure.propertyName.assert().isEqualTo("template")
    }

    @Test
    fun `a failed simulation of a missing index fails the load`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.error(indexNotFound())
        simulation(
            CompletableFuture.failedFuture(
                IllegalStateException("forbidden"),
            )
        )

        ElasticsearchIndexMappingResolver(client).currentOrLoad(INDEX).test().expectErrorMessage("forbidden").verify()
    }

    @Test
    fun `a deleted index drops its cached mapping`() {
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(mappingResponse(field = "code")),
            Mono.error(indexNotFound()),
        )
        simulation(
            CompletableFuture.failedFuture(
                MissingRequiredPropertyException(SimulateIndexTemplateResponse::class.java, "template"),
            )
        )
        val resolver = ElasticsearchIndexMappingResolver(client)
        resolver.currentOrLoad(INDEX).block()

        resolver.refresh(INDEX).block()!!.fields.assert().isEmpty()
        resolver.currentOrLoad(INDEX).block()!!.fields.assert().isEmpty()

        verify(exactly = 3) { indicesClient.getMapping(any<GetMappingRequest>()) }
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

    /**
     * An alias over rollover indices, or a data stream: the fields every index maps alike are queryable; one that a
     * newer index added, or that two indices map differently, is left out until every index maps it the same way.
     */
    @Test
    fun `several physical indices share the fields they map alike`() {
        val older = TypeMapping.of { type ->
            type.properties("name") { it.keyword { keyword -> keyword } }
                .properties("token") { it.keyword { keyword -> keyword.nullValue("NULL") } }
                .properties("amount") { it.long_ { long -> long } }
        }
        val newer = TypeMapping.of { type ->
            type.properties("name") { it.keyword { keyword -> keyword } }
                .properties("token") { it.keyword { keyword -> keyword.nullValue("NULL") } }
                .properties("amount") { it.double_ { double -> double } }
                .properties("added") { it.keyword { keyword -> keyword } }
        }
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            GetMappingResponse.of { response ->
                response.mappings("$INDEX-000001", IndexMappingRecord.of { it.mappings(older) })
                    .mappings("$INDEX-000002", IndexMappingRecord.of { it.mappings(newer) })
            },
        )

        ElasticsearchIndexMappingResolver(client).currentOrLoad(INDEX).test()
            .consumeNextWith { mapping ->
                mapping.indexName.assert().isEqualTo(INDEX)
                mapping.fields.keys.assert().containsExactlyInAnyOrder("name", "token")
                mapping.provisional.assert().isFalse()
            }.verifyComplete()
    }

    @Test
    fun `physical indices whose source settings differ fail closed`() {
        val excluded = TypeMapping.of { type ->
            type.source { it.excludes("secret") }.properties("name") { it.keyword { keyword -> keyword } }
        }
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            GetMappingResponse.of { response ->
                response.mappings("$INDEX-000001", IndexMappingRecord.of { it.mappings(mapping("name")) })
                    .mappings("$INDEX-000002", IndexMappingRecord.of { it.mappings(excluded) })
            },
        )

        ElasticsearchIndexMappingResolver(client).currentOrLoad(INDEX).test()
            .expectErrorMatches {
                it.message!!.startsWith("Elasticsearch index [$INDEX] resolves to physical indices whose _source")
            }.verify()
    }

    @Test
    fun `object and nested containers should not be indexed`() {
        val mapping = TypeMapping.of { type ->
            type.properties("state") { state ->
                state.`object` { objectField ->
                    objectField.properties("name") { name -> name.keyword { it } }
                }
            }.properties("orders") { orders ->
                orders.nested { nested ->
                    nested.properties("status") { status -> status.keyword { it } }
                }
            }
        }

        val fields = ElasticsearchIndexMapping.from(INDEX, mapping).fields

        fields.getValue("state").kind.assert().isEqualTo(Property.Kind.Object)
        fields.getValue("state").indexed.assert().isFalse()
        fields.getValue("orders").kind.assert().isEqualTo(Property.Kind.Nested)
        fields.getValue("orders").indexed.assert().isFalse()
        fields.getValue("state.name").indexed.assert().isTrue()
        fields.getValue("orders.status").indexed.assert().isTrue()
    }

    @Test
    fun `runtime fields should not expose source projection paths`() {
        val mapping = TypeMapping.of { type ->
            type.runtime("runtimeCode") { it.type(RuntimeFieldType.Keyword) }
                .runtime("runtime") { runtime ->
                    runtime.type(RuntimeFieldType.Composite)
                        .fields("code") { it.type(RuntimeFieldType.Keyword) }
                }
        }

        val fields = ElasticsearchIndexMapping.from(INDEX, mapping).fields

        fields.getValue("runtimeCode").projectionPath.assert().isNull()
        fields.getValue("runtime.code").projectionPath.assert().isNull()
    }

    private fun simulation(response: CompletableFuture<SimulateIndexTemplateResponse>) {
        every { transport.performRequestAsync(any<SimulateIndexTemplateRequest>(), any<SimulateEndpoint>(), any()) } returns
            response
    }

    private fun mappingResponse(field: String): GetMappingResponse = GetMappingResponse.of { response ->
        response.mappings(INDEX, IndexMappingRecord.of { record -> record.mappings(mapping(field)) })
    }

    private fun mapping(field: String): TypeMapping = TypeMapping.of { mapping ->
        mapping.properties(field) { it.keyword { keyword -> keyword } }
    }

    companion object {
        private const val INDEX = "wow.catalog.sku.snapshot"

        internal fun indexNotFound(): ElasticsearchException = ElasticsearchException(
            "indices.get_mapping",
            ErrorResponse.of { response ->
                response.status(404).error { error -> error.type("index_not_found_exception").reason("no such index") }
            },
        )
    }
}
