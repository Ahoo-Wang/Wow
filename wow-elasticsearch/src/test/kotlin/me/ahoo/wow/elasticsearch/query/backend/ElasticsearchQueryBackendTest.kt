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

import co.elastic.clients.elasticsearch._types.mapping.DynamicTemplate
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsResponse
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.elasticsearch.indices.IndexSettings
import co.elastic.clients.json.JsonData
import co.elastic.clients.transport.endpoints.BooleanResponse
import co.elastic.clients.util.DateTime
import co.elastic.clients.util.NamedValue
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
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
    fun `ordinary integer mapping is not ready before search or pit io`() {
        val client = mappingClient(
            mapOf("rank" to Property.of { it.integer { value -> value } }),
        )
        val expression = PredicateExpression(
            PortableQueryDataset.RANK,
            PortableOperator.EQ,
            listOf(QueryValue.IntegerValue(Long.MAX_VALUE)),
        )
        val backend = ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                expression,
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
    fun `empty collection readiness requires a managed exact scalar collection mapping`() {
        val expression = PredicateExpression(
            PortableQueryDataset.NULLABLE_TAGS,
            PortableOperator.EMPTY_COLLECTION,
            emptyList(),
        )
        val presence = Property.of { property ->
            property.`object` { obj ->
                obj.properties("present") { it.keyword { value -> value } }
                    .properties("null") { it.keyword { value -> value } }
            }
        }
        val valid = mappingClient(
            mapOf(
                "nullableLabels" to Property.of { it.keyword { value -> value } },
                "__wow_query" to presence,
            ),
        )
        val text = mappingClient(
            mapOf(
                "nullableLabels" to Property.of { it.text { value -> value } },
                "__wow_query" to presence,
            ),
        )

        StepVerifier.create(backend(valid, expression).readiness())
            .expectNext(QueryBackendReadiness.Ready)
            .verifyComplete()
        StepVerifier.create(backend(text, expression).readiness())
            .expectNext(
                QueryBackendReadiness.NotReady(
                    me.ahoo.wow.query.backend.QueryBackendReadinessReason.MAPPING_INCOMPATIBLE,
                ),
            ).verifyComplete()
        verify(exactly = 0) { text.search(any<SearchRequest>(), Map::class.java) }
        verify(exactly = 0) { text.openPointInTime(any<OpenPointInTimeRequest>()) }
    }

    @Test
    fun `binary empty collection is configuration invalid before index io`() {
        val client = mockk<ReactiveElasticsearchClient>(relaxed = true)
        val field = LogicalField("payloads")
        val source = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)
        val schema = source.withField(
            me.ahoo.wow.query.schema.QueryFieldSchema(
                field,
                me.ahoo.wow.query.schema.QueryFieldValueKind.BINARY,
                nullable = true,
                collectionKind = me.ahoo.wow.query.schema.QueryCollectionKind.SCALAR,
            ),
        )
        val backend = ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                source.target,
                schema,
                PredicateExpression(field, PortableOperator.EMPTY_COLLECTION, emptyList()),
            ),
        )

        StepVerifier.create(backend.readiness())
            .expectNext(
                QueryBackendReadiness.NotReady(
                    me.ahoo.wow.query.backend.QueryBackendReadinessReason.CONFIGURATION_INVALID,
                ),
            ).verifyComplete()
        confirmVerified(client)
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
    fun `managed presence fallback with disabled ancestor is not ready before search or pit io`() {
        val rootMetadata = Property.of { property ->
            property.`object` { objectProperty ->
                objectProperty.properties("present") { it.keyword { keyword -> keyword } }
                    .properties("null") { it.keyword { keyword -> keyword } }
            }
        }
        val client = mappingClient(
            properties = mapOf(
                "profile" to Property.of { property ->
                    property.`object` { objectProperty -> objectProperty.dynamic(co.elastic.clients.elasticsearch._types.mapping.DynamicMapping.False) }
                },
                "__wow_query" to rootMetadata,
            ),
            configureMapping = {
                meta("wow_query_presence_template_version", JsonData.of(1))
                    .dynamicTemplates(managedPresenceTemplates())
            },
        )
        val backend = ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                PredicateExpression(PortableQueryDataset.PROFILE_CITY, PortableOperator.NULL, emptyList()),
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
    fun `explicit presence marker with disabled ancestor is not ready before search or pit io`() {
        val metadata = Property.of { property ->
            property.`object` { objectProperty ->
                objectProperty.properties("present") { it.keyword { keyword -> keyword } }
                    .properties("null") { it.keyword { keyword -> keyword } }
            }
        }
        val client = mappingClient(
            properties = mapOf(
                "profile" to Property.of { property ->
                    property.`object` { objectProperty ->
                        objectProperty.enabled(false).properties("__wow_query", metadata)
                    }
                },
                "__wow_query" to metadata,
            ),
            configureMapping = {
                meta("wow_query_presence_template_version", JsonData.of(1))
                    .dynamicTemplates(managedPresenceTemplates())
            },
        )
        val backend = ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                PredicateExpression(PortableQueryDataset.PROFILE_CITY, PortableOperator.NULL, emptyList()),
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

    @Test
    fun `managed exact mapping counterexamples are not ready before search or pit io`() {
        listOf(
            Property.of { it.constantKeyword { value -> value } },
            Property.of { it.constantKeyword { value -> value.value(JsonData.of("fixed")) } },
            Property.of { it.keyword { value -> value.nullValue("missing") } },
        ).forEach { property ->
            assertRejected(
                mapOf("logicalId" to property),
                PredicateExpression(
                    PortableQueryDataset.LOGICAL_ID,
                    PortableOperator.EQ,
                    listOf(QueryValue.StringValue("d01")),
                ),
            )
        }
        assertRejected(
            mapOf("status" to Property.of { it.constantKeyword { value -> value.value(JsonData.of("PROCESSING")) } }),
            PredicateExpression(
                PortableQueryDataset.STATUS,
                PortableOperator.EQ,
                listOf(QueryValue.EnumValue("PROCESSING")),
            ),
        )
        listOf(
            "enabled" to Property.of { it.boolean_ { value -> value.nullValue(false) } },
            "rank" to Property.of { it.long_ { value -> value.nullValue(0) } },
            "score" to Property.of { it.double_ { value -> value.nullValue(0.0) } },
            "createdAt" to Property.of {
                it.date { value -> value.nullValue(DateTime.of("1970-01-01T00:00:00Z")) }
            },
        ).forEach { (path, property) ->
            val (field, queryValue) = when (path) {
                "enabled" -> PortableQueryDataset.ENABLED to QueryValue.BooleanValue(true)
                "rank" -> PortableQueryDataset.RANK to QueryValue.IntegerValue(1)
                "score" -> PortableQueryDataset.SCORE to QueryValue.DecimalValue(java.math.BigDecimal.ONE)
                else -> PortableQueryDataset.CREATED_AT to QueryValue.InstantValue(java.time.Instant.EPOCH)
            }
            assertRejected(
                mapOf(path to property),
                PredicateExpression(field, PortableOperator.EQ, listOf(queryValue)),
            )
        }
    }

    @Test
    fun `managed analyzer counterexamples are not ready before search or pit io`() {
        listOf<(IndexSettings.Builder) -> Unit>(
            { settings ->
                settings.analysis { analysis ->
                    analysis.analyzer("default_search") { analyzer -> analyzer.keyword { value -> value } }
                }
            },
            { settings ->
                settings.analysis { analysis ->
                    analysis.analyzer("default_search") { analyzer ->
                        analyzer.standard { value -> value.maxTokenLength(1) }
                    }
                }
            },
        ).forEach { configure ->
            assertRejected(
                mapOf("title" to Property.of { it.text { value -> value } }),
                FullTextExpression(
                    QueryCapabilityId(ElasticsearchQueryBackendFactory.FULL_TEXT_CAPABILITY),
                    "portable",
                    setOf(PortableQueryDataset.TITLE),
                ),
            ) {
                configure(this)
            }
        }
    }

    private fun assertRejected(
        properties: Map<String, Property>,
        expression: QueryExpression,
        configureSettings: IndexSettings.Builder.() -> Unit = {},
    ) {
        val client = mappingClient(properties, configureSettings)
        val backend = ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                expression,
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

    private fun backend(client: ReactiveElasticsearchClient, expression: QueryExpression) =
        ElasticsearchQueryBackendFactory(client).bind(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
                PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
                expression,
            ),
        )

    private fun mappingClient(
        properties: Map<String, Property>,
        configureSettings: IndexSettings.Builder.() -> Unit = {},
        configureMapping: co.elastic.clients.elasticsearch._types.mapping.TypeMapping.Builder.() -> Unit = {},
    ): ReactiveElasticsearchClient {
        val client = mockk<ReactiveElasticsearchClient>(relaxed = true)
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.indices() } returns indices
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(true))
        every { indices.getMapping(any<GetMappingRequest>()) } returns Mono.just(
            GetMappingResponse.of { response ->
                response.mappings("index") { record ->
                    record.mappings { mapping ->
                        mapping.meta("wow_query_presence_version", JsonData.of(1))
                            .properties(properties)
                            .apply(configureMapping)
                    }
                }
            },
        )
        every { indices.getSettings(any<GetIndicesSettingsRequest>()) } returns Mono.just(
            GetIndicesSettingsResponse.of { response ->
                response.settings("index") { state -> state.settings { settings -> settings.apply(configureSettings) } }
            },
        )
        return client
    }

    private fun managedPresenceTemplates(): List<NamedValue<DynamicTemplate>> = listOf(
        presenceTemplate("wow_query_present_keyword", "present"),
        presenceTemplate("wow_query_null_keyword", "null"),
    )

    private fun presenceTemplate(
        name: String,
        marker: String,
    ): NamedValue<DynamicTemplate> = NamedValue.of(
        name,
        DynamicTemplate.of { template ->
            template.pathMatch("__wow_query.$marker", "*.__wow_query.$marker")
                .matchMappingType("string")
                .mapping { property -> property.keyword { keyword -> keyword } }
        },
    )
}
