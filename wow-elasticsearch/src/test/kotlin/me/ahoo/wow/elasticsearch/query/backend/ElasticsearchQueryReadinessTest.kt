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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendReadinessReason
import me.ahoo.wow.query.schema.QueryFieldValueKind
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
            fields = setOf(
                field("logicalId", QueryFieldValueKind.STRING, ElasticsearchMappingUsage.EXACT),
                field("title", QueryFieldValueKind.STRING, ElasticsearchMappingUsage.SEARCH),
                field("logicalId", QueryFieldValueKind.STRING, ElasticsearchMappingUsage.SORT),
                field("items", QueryFieldValueKind.OBJECT, ElasticsearchMappingUsage.NESTED),
            ),
            presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
            presenceFields = setOf(
                "__wow_query.present",
                "__wow_query.null",
                "items.__wow_query.present",
                "items.__wow_query.null",
            ),
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
    fun `mapping enforces scalar kind time wire analyzer and accepts binary`() {
        val requirements = ElasticsearchQueryReadinessRequirements(
            configurationValid = true,
            fields = setOf(
                field("rank", QueryFieldValueKind.INTEGER, ElasticsearchMappingUsage.EXACT),
                field("createdAt", QueryFieldValueKind.TIME, ElasticsearchMappingUsage.EXACT, system = false),
                field("payload", QueryFieldValueKind.BINARY, ElasticsearchMappingUsage.EXACT),
                field("title", QueryFieldValueKind.STRING, ElasticsearchMappingUsage.SEARCH),
            ),
            presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
        )
        fun scalarMapping(
            rank: Property = Property.of { it.long_ { value -> value } },
            createdAt: Property = Property.of { it.date { value -> value } },
            title: Property = Property.of { it.text { value -> value } },
        ) = mapping(
            ElasticsearchQueryPresenceEncoder.VERSION,
            mapOf(
                "rank" to rank,
                "createdAt" to createdAt,
                "payload" to Property.of { it.binary { value -> value } },
                "title" to title,
            ),
        )

        StepVerifier.create(readiness(requirements, scalarMapping()).inspect())
            .expectNext(QueryBackendReadiness.Ready)
            .verifyComplete()
        listOf(
            scalarMapping(rank = Property.of { it.keyword { value -> value } }),
            scalarMapping(createdAt = Property.of { it.long_ { value -> value } }),
            scalarMapping(title = Property.of { it.text { value -> value.analyzer("custom") } }),
        ).forEach { incompatible ->
            StepVerifier.create(readiness(requirements, incompatible).inspect())
                .expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE))
                .verifyComplete()
        }
    }

    @Test
    fun `source string accepts text encoding but rejects a different scalar kind`() {
        val requirements = ElasticsearchQueryReadinessRequirements(
            configurationValid = true,
            fields = setOf(field("title", QueryFieldValueKind.STRING, ElasticsearchMappingUsage.SOURCE)),
            presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
        )

        StepVerifier.create(
            readiness(
                requirements,
                mapping(
                    ElasticsearchQueryPresenceEncoder.VERSION,
                    mapOf("title" to Property.of { it.text { value -> value } }),
                ),
            ).inspect(),
        ).expectNext(QueryBackendReadiness.Ready).verifyComplete()

        StepVerifier.create(
            readiness(
                requirements,
                mapping(
                    ElasticsearchQueryPresenceEncoder.VERSION,
                    mapOf("title" to Property.of { it.long_ { value -> value } }),
                ),
            ).inspect(),
        ).expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE)).verifyComplete()
    }

    @Test
    fun `source object collection retains nested mapping shape`() {
        val requirements = ElasticsearchQueryReadinessRequirements(
            configurationValid = true,
            fields = setOf(
                ElasticsearchMappingFieldRequirement(
                    "items",
                    QueryFieldValueKind.OBJECT,
                    me.ahoo.wow.query.schema.QueryCollectionKind.OBJECT,
                    system = false,
                    usage = ElasticsearchMappingUsage.SOURCE,
                ),
            ),
            presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
        )

        StepVerifier.create(
            readiness(
                requirements,
                mapping(
                    ElasticsearchQueryPresenceEncoder.VERSION,
                    mapOf("items" to Property.of { it.`object` { value -> value } }),
                ),
            ).inspect(),
        ).expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE)).verifyComplete()
        StepVerifier.create(
            readiness(
                requirements,
                mapping(
                    ElasticsearchQueryPresenceEncoder.VERSION,
                    mapOf("items" to Property.of { it.nested { value -> value } }),
                ),
            ).inspect(),
        ).expectNext(QueryBackendReadiness.Ready).verifyComplete()
    }

    @Test
    fun `sort and result requirements reuse the inspected mapping snapshot`() {
        val requirements = ElasticsearchQueryReadinessRequirements(
            configurationValid = true,
            fields = emptySet(),
            presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
        )
        val guard = readiness(
            requirements,
            mapping(
                ElasticsearchQueryPresenceEncoder.VERSION,
                mapOf(
                    "logicalId" to Property.of { it.keyword { value -> value } },
                    "rank" to Property.of { it.keyword { value -> value } },
                    "score" to Property.of { it.long_ { value -> value } },
                ),
            ),
        )
        StepVerifier.create(guard.inspect()).expectNext(QueryBackendReadiness.Ready).verifyComplete()

        listOf(
            field("rank", QueryFieldValueKind.INTEGER, ElasticsearchMappingUsage.SORT),
            field("score", QueryFieldValueKind.DECIMAL, ElasticsearchMappingUsage.SOURCE),
        ).forEach { incompatible ->
            val error = org.junit.jupiter.api.assertThrows<me.ahoo.wow.api.query.error.QueryException> {
                guard.requireFields(setOf(incompatible))
            }
            error.code.assert().isEqualTo(me.ahoo.wow.api.query.error.QueryErrorCode.BACKEND_NOT_READY)
        }
    }

    @Test
    fun `missing or wrong null metadata is incompatible`() {
        val requirements = ElasticsearchQueryReadinessRequirements(
            configurationValid = true,
            fields = emptySet(),
            presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
            presenceFields = setOf("__wow_query.present", "__wow_query.null"),
        )
        listOf(
            metadataProperty(includeNull = false),
            metadataProperty(nullProperty = Property.of { it.text { value -> value } }),
        ).forEach { metadata ->
            val incompatible = mapping(
                ElasticsearchQueryPresenceEncoder.VERSION,
                mapOf("__wow_query" to metadata),
            )
            StepVerifier.create(readiness(requirements, incompatible).inspect())
                .expectNext(QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE))
                .verifyComplete()
        }
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
            configurationValid = true,
            fields = emptySet(),
            presenceVersion = 1,
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

    private fun metadataProperty(
        includeNull: Boolean = true,
        nullProperty: Property = Property.of { it.keyword { keyword -> keyword } },
    ): Property = Property.of { property ->
        property.`object` { obj ->
            obj.properties("present") { it.keyword { keyword -> keyword } }.apply {
                if (includeNull) properties("null", nullProperty)
            }
        }
    }

    private fun field(
        path: String,
        kind: QueryFieldValueKind,
        usage: ElasticsearchMappingUsage,
        system: Boolean = false,
    ) = ElasticsearchMappingFieldRequirement(
        path,
        kind,
        me.ahoo.wow.query.schema.QueryCollectionKind.NONE,
        system,
        usage
    )

    companion object {
        private const val PRESENCE_VERSION_META = "wow_query_presence_version"
    }
}
