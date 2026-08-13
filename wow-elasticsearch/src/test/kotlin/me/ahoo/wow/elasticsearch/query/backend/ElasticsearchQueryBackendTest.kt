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
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsResponse
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.json.JsonData
import co.elastic.clients.transport.endpoints.BooleanResponse
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.schema.QueryBackendFieldPath
import me.ahoo.wow.query.schema.QueryBackendId
import me.ahoo.wow.query.schema.QueryCapabilityBinding
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class ElasticsearchQueryBackendTest {
    @Test
    fun `factory binds synchronously without client io and advertises exact capabilities`() {
        val client = mockk<ReactiveElasticsearchClient>(relaxed = true)
        val budget = QueryBudgetLimit(maxResults = 512)
        val factory = ElasticsearchQueryBackendFactory(client, maxBudget = budget)
        val target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)

        val backend = factory.bind(
            QueryBackendResolutionContext(
                target,
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                MatchAll,
            ),
        )

        backend.descriptor.backendId.assert().isEqualTo("elasticsearch")
        backend.descriptor.documentKinds.assert().isEqualTo(QueryDocumentKind.entries.toSet())
        backend.descriptor.planVersions.assert().isEqualTo(setOf(QueryPlanVersion.V1))
        backend.descriptor.capabilities.assert().isEqualTo(
            setOf(
                QueryCapabilityId("full-text"),
                QueryCapabilityId("x-wow:elasticsearch-native"),
            ),
        )
        backend.descriptor.maxBudget.assert().isEqualTo(budget)
        confirmVerified(client)
    }

    @Test
    fun `readiness gates only current expression dependencies`() {
        val client = mappingClient(
            mapOf(
                "rank" to Property.of { it.long_ { value -> value } },
                "title" to Property.of { it.keyword { value -> value } },
            ),
        )
        val expression = PredicateExpression(
            PortableQueryDataset.RANK,
            PortableOperator.EQ,
            listOf(QueryValue.IntegerValue(1)),
        )
        val backend = ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                expression,
            ),
        )

        StepVerifier.create(backend.readiness()).expectNext(QueryBackendReadiness.Ready).verifyComplete()
    }

    @Test
    fun `null metadata mismatch is not ready and performs no search or pit io`() {
        val client = mappingClient(
            mapOf(
                "nullableText" to Property.of { it.keyword { value -> value } },
                "__wow_query" to Property.of { property ->
                    property.`object` { obj ->
                        obj.properties("present") { it.keyword { value -> value } }
                    }
                },
            ),
        )
        val expression = PredicateExpression(PortableQueryDataset.NULLABLE_TEXT, PortableOperator.NULL, emptyList())
        val backend = ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                expression,
            ),
        )

        StepVerifier.create(backend.readiness())
            .expectNextMatches { readiness -> readiness is QueryBackendReadiness.NotReady }
            .verifyComplete()
        verify(exactly = 0) { client.openPointInTime(any<OpenPointInTimeRequest>()) }
    }

    @Test
    fun `source divergent nested binding is configuration invalid before index io`() {
        val client = mockk<ReactiveElasticsearchClient>(relaxed = true)
        val source = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)
        val items = source.fields.getValue(PortableQueryDataset.ITEMS).copy(
            bindings = setOf(
                QueryCapabilityBinding(
                    QueryBackendId("elasticsearch"),
                    QueryFieldUsage.NESTED,
                    QueryBackendFieldPath("line_items"),
                ),
            ),
        )
        val schema = source.withField(items)
        val backend = ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                schema,
                ElementMatchExpression(
                    PortableQueryDataset.ITEMS,
                    PredicateExpression(
                        LogicalField("sku"),
                        PortableOperator.EQ,
                        listOf(QueryValue.StringValue("A")),
                    ),
                ),
            ),
        )

        StepVerifier.create(backend.readiness())
            .expectNextMatches { readiness -> readiness is QueryBackendReadiness.NotReady }
            .verifyComplete()
        confirmVerified(client)
    }

    @Test
    fun `element match rejects ordinary object mapping before search or pit io`() {
        val client = mappingClient(
            mapOf(
                "items" to Property.of { property ->
                    property.`object` { obj ->
                        obj.properties("sku") { value -> value.keyword { keyword -> keyword } }
                    }
                },
            ),
        )
        val backend = ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                ElementMatchExpression(
                    PortableQueryDataset.ITEMS,
                    PredicateExpression(
                        LogicalField("sku"),
                        PortableOperator.EQ,
                        listOf(QueryValue.StringValue("A")),
                    ),
                ),
            ),
        )

        StepVerifier.create(backend.readiness())
            .expectNext(
                QueryBackendReadiness.NotReady(
                    me.ahoo.wow.query.backend.QueryBackendReadinessReason.MAPPING_INCOMPATIBLE,
                ),
            )
            .verifyComplete()
        verify(exactly = 0) { client.search(any<SearchRequest>(), Map::class.java) }
        verify(exactly = 0) { client.openPointInTime(any<OpenPointInTimeRequest>()) }
    }

    @Test
    fun `binary predicate is configuration invalid before index search or pit io`() {
        val client = mappingClient(
            mapOf("payload" to Property.of { it.binary { value -> value } }),
        )
        val payload = LogicalField("payload")
        val schema = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT).withField(
            me.ahoo.wow.query.schema.QueryFieldSchema(
                payload,
                me.ahoo.wow.query.schema.QueryFieldValueKind.BINARY,
                nullable = false,
            ),
        )
        val backend = ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                schema,
                PredicateExpression(
                    payload,
                    PortableOperator.EQ,
                    listOf(QueryValue.BinaryValue(byteArrayOf(1, 2, 3))),
                ),
            ),
        )

        StepVerifier.create(backend.readiness())
            .expectNext(
                QueryBackendReadiness.NotReady(
                    me.ahoo.wow.query.backend.QueryBackendReadinessReason.CONFIGURATION_INVALID,
                ),
            )
            .verifyComplete()
        verify(exactly = 0) { client.indices().exists(any<ExistsRequest>()) }
        verify(exactly = 0) { client.search(any<SearchRequest>(), Map::class.java) }
        verify(exactly = 0) { client.openPointInTime(any<OpenPointInTimeRequest>()) }
    }

    private fun mappingClient(properties: Map<String, Property>): ReactiveElasticsearchClient {
        val client = mockk<ReactiveElasticsearchClient>(relaxed = true)
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.indices() } returns indices
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(true))
        every { indices.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            GetMappingResponse.of { response ->
                response.mappings("index") { record ->
                    record.mappings { mapping ->
                        mapping.meta("wow_query_presence_version", JsonData.of(1)).properties(properties)
                    }
                }
            },
        )
        every { indices.getSettings(any<GetIndicesSettingsRequest>()) } returns Mono.just(
            GetIndicesSettingsResponse.of { response ->
                response.settings("index") { state -> state.settings { settings -> settings } }
            },
        )
        return client
    }
}
