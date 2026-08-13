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

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.json.JsonData
import co.elastic.clients.transport.endpoints.BooleanResponse
import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendReadinessReason
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class ElasticsearchQueryReadinessTest {
    @Test
    fun `mapping must satisfy exact search sort nested and recursive presence contract`() {
        val requirements = ElasticsearchQueryReadinessRequirements(
            configurationValid = true,
            exactFields = setOf("logicalId"),
            searchFields = setOf("title"),
            sortFields = setOf("logicalId"),
            nestedFields = setOf("items"),
            presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
            presenceFields = setOf("__wow_query.present", "items.__wow_query.present"),
        )
        val ready = mapping(
            version = ElasticsearchQueryPresenceEncoder.VERSION,
            rootProperties = mapOf(
                "logicalId" to Property.of { it.keyword { keyword -> keyword } },
                "title" to Property.of { it.text { text -> text } },
                "items" to Property.of { property ->
                    property.nested { nested ->
                        nested.properties("__wow_query", metadataProperty())
                    }
                },
                "__wow_query" to metadataProperty(),
            ),
        )

        StepVerifier.create(readiness(requirements, ready).inspect())
            .expectNext(QueryBackendReadiness.Ready)
            .verifyComplete()

        val incompatible = mapping(
            version = ElasticsearchQueryPresenceEncoder.VERSION - 1,
            rootProperties = mapOf(
                "logicalId" to Property.of { it.text { text -> text } },
                "title" to Property.of { it.keyword { keyword -> keyword } },
                "items" to Property.of { it.`object` { nested -> nested } },
                "__wow_query" to metadataProperty(),
            ),
        )
        StepVerifier.create(readiness(requirements, incompatible).inspect())
            .expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE))
            .verifyComplete()
    }

    @Test
    fun `missing index and dependency failure stay distinguishable`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.indices() } returns indices
        every { indices.exists(any<ExistsRequest>()) } returnsMany listOf(
            Mono.just(BooleanResponse(false)),
            Mono.error(IllegalStateException("down")),
        )
        val requirements = ElasticsearchQueryReadinessRequirements(
            true,
            emptySet(),
            emptySet(),
            emptySet(),
            emptySet(),
            1,
        )

        StepVerifier.create(ElasticsearchQueryReadiness(client, "index", requirements).inspect())
            .expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING))
            .verifyComplete()
        StepVerifier.create(ElasticsearchQueryReadiness(client, "index", requirements).inspect())
            .expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.DEPENDENCY_UNAVAILABLE))
            .verifyComplete()
    }

    private fun readiness(
        requirements: ElasticsearchQueryReadinessRequirements,
        mapping: GetMappingResponse,
    ): ElasticsearchQueryReadiness {
        val client = mockk<ReactiveElasticsearchClient>()
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.indices() } returns indices
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(true))
        every { indices.getMapping(any<GetMappingRequest>()) } returns Mono.just(mapping)
        return ElasticsearchQueryReadiness(client, "index", requirements)
    }

    private fun mapping(version: Int, rootProperties: Map<String, Property>): GetMappingResponse =
        GetMappingResponse.of { response ->
            response.mappings("index") { record ->
                record.mappings { type ->
                    type.meta(PRESENCE_VERSION_META, JsonData.of(version))
                        .properties(rootProperties)
                }
            }
        }

    private fun metadataProperty(): Property = Property.of { property ->
        property.`object` { obj ->
            obj.properties("present") { it.keyword { keyword -> keyword } }
                .properties("null") { it.keyword { keyword -> keyword } }
        }
    }

    companion object {
        private const val PRESENCE_VERSION_META = "wow_query_presence_version"
    }
}
