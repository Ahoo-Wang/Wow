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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.elasticsearch.query.planned

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.json.JsonData
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.EmptyArraySemantics
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.QuerySearchScopeDefinition
import me.ahoo.wow.query.backend.SearchScopeId
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

class ElasticsearchSnapshotQueryBindingTest {
    @Test
    fun `readiness should attest every concrete mapping and explicit physical role`() {
        binding.prepared.attestReadiness(
            mapOf(
                "sales.order.snapshot-000001" to mapping(),
                "sales.order.snapshot-000002" to mapping(),
            ),
        )

        binding.fields[name]!!.exactField.assert().isEqualTo("state.name.exact")
        binding.searchScopes[scope]!!.fields[name].assert().isEqualTo("state.name")

        val contribution = binding.prepared.createContribution(mockk<ReactiveElasticsearchClient>())
        contribution.supportedOperations.assert().contains(QueryOperation.ANALYZE)
        contribution.analyticsBackend.assert().isNotNull()
    }

    @Test
    fun `readiness should reject mapping version and field role drift`() {
        assertThrownBy<ElasticsearchQueryBackendNotReadyException> {
            binding.prepared.attestReadiness(mapOf("generation" to mapping(version = "stale")))
        }
        assertThrownBy<ElasticsearchQueryBackendNotReadyException> {
            binding.prepared.attestReadiness(
                mapOf("generation" to mapping(nameExact = keyword(ignoreAbove = 63))),
            )
        }
        assertThrownBy<ElasticsearchQueryBackendNotReadyException> {
            binding.prepared.attestReadiness(
                mapOf("generation" to mapping(nameExact = keyword(ignoreAbove = 128, docValues = false))),
            )
        }
        assertThrownBy<ElasticsearchQueryBackendNotReadyException> {
            binding.prepared.attestReadiness(
                mapOf("generation" to mapping(searchAnalyzer = "whitespace")),
            )
        }
        assertThrownBy<ElasticsearchQueryBackendNotReadyException> {
            binding.prepared.attestReadiness(
                mapOf("generation" to mapping(nameExact = keyword(128, normalizer = "lowercase"))),
            )
        }
        assertThrownBy<ElasticsearchQueryBackendNotReadyException> {
            binding.prepared.attestReadiness(
                mapOf("generation" to mapping(nameExact = keyword(128, nullValue = "__null__"))),
            )
        }
    }

    @Test
    fun `readiness should reject object arrays masquerading as nested mappings`() {
        assertThrownBy<ElasticsearchQueryBackendNotReadyException> {
            binding.prepared.attestReadiness(mapOf("generation" to mapping(nestedItems = false)))
        }
    }

    @Test
    fun `readiness should reject sort and group roles whose mapping type differs from the logical field`() {
        val mismatchedFields = binding.fields.toMutableMap()
        mismatchedFields[name] = ElasticsearchFieldBinding(
            "state.name",
            binding.fields.getValue(name).capabilities,
            exactField = "state.name.exact",
            searchField = "state.name",
            searchAnalyzer = "standard",
            literalField = "state.name.exact",
            sortField = "state.rank",
            groupField = "state.rank",
            groupReadiness = GROUP_READINESS,
            keywordReadiness = KEYWORD_READINESS,
        )
        val mismatchedBinding = ElasticsearchSnapshotQueryBinding(
            schema,
            INDEX,
            VERSION,
            mismatchedFields,
            listOf(searchBinding),
        )

        assertThrownBy<ElasticsearchQueryBackendNotReadyException> {
            mismatchedBinding.prepared.attestReadiness(mapOf("generation" to mapping(includeRank = true)))
        }
    }

    @Test
    fun `binding should reject guessed physical roles and incomplete keyword attestation`() {
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchFieldBinding(
                "state.name",
                setOf(FieldCapability.EXACT),
            )
        }
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchKeywordReadiness(128, 512, historicalValuesAudited = false, writeConstraintEnforced = true)
        }
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchFieldBinding(
                "state.note",
                setOf(FieldCapability.PRESENCE),
            )
        }
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchSnapshotQueryBinding(
                schema,
                INDEX,
                VERSION,
                binding.fields + (
                    QueryFieldId.System(SystemFieldKind.TENANT_ID) to ElasticsearchFieldBinding(
                        MessageRecords.OWNER_ID,
                        setOf(FieldCapability.EXACT),
                        exactField = MessageRecords.OWNER_ID,
                        keywordReadiness = KEYWORD_READINESS,
                    )
                    ),
                listOf(searchBinding),
            )
        }
    }

    private fun mapping(
        version: String = VERSION,
        nameExact: Property = keyword(ignoreAbove = 128),
        nestedItems: Boolean = true,
        includeRank: Boolean = false,
        analyzer: String = "standard",
        searchAnalyzer: String = "standard",
    ): TypeMapping = TypeMapping.of { mapping ->
        mapping.meta(MAPPING_VERSION_META, JsonData.of(version))
            .meta(DOCUMENT_KIND_META, JsonData.of(QueryDocumentKind.SNAPSHOT.name))
            .meta(SCHEMA_CONTRACT_META, JsonData.of(schema.contractId.value))
            .meta(CAPABILITY_DIGEST_META, JsonData.of(binding.prepared.capabilityDigest))
            .properties(MessageRecords.AGGREGATE_ID, keyword(ignoreAbove = 128))
            .properties(MessageRecords.TENANT_ID, keyword(ignoreAbove = 128))
            .properties(
                "state",
                Property.of { state ->
                    state.`object` { objectField ->
                        objectField
                            .properties(
                                "name",
                                Property.of { text ->
                                    text.text { definition ->
                                        definition.analyzer(analyzer).searchAnalyzer(searchAnalyzer)
                                            .fields("exact", nameExact)
                                    }
                                },
                            )
                            .properties(
                                "items",
                                if (nestedItems) {
                                    Property.of { items ->
                                        items.nested { nested -> nested.properties("name", keyword(ignoreAbove = 128)) }
                                    }
                                } else {
                                    Property.of { items ->
                                        items.`object` { objectField ->
                                            objectField.properties("name", keyword(ignoreAbove = 128))
                                        }
                                    }
                                },
                            )
                            .also { state ->
                                if (includeRank) {
                                    state.properties(
                                        "rank",
                                        Property.of { property -> property.boolean_ { boolean -> boolean } },
                                    )
                                }
                            }
                    }
                },
            )
    }

    private fun keyword(
        ignoreAbove: Int,
        docValues: Boolean = true,
        normalizer: String? = null,
        nullValue: String? = null,
    ): Property =
        Property.of { property ->
            property.keyword { keyword ->
                keyword.ignoreAbove(ignoreAbove).docValues(docValues).also { definition ->
                    normalizer?.let(definition::normalizer)
                    nullValue?.let(definition::nullValue)
                }
            }
        }

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
    private val tenant = QueryFieldId.System(SystemFieldKind.TENANT_ID)
    private val state = QueryFieldId.Path(listOf("state"))
    private val name = QueryFieldId.Path(listOf("state", "name"))
    private val items = QueryFieldId.Path(listOf("state", "items"))
    private val itemName = QueryFieldId.Path(listOf("state", "items", "name"))
    private val scope = SearchScopeId("state-name")
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            field(identity, LogicalFieldType.Text, setOf(PredicateOperator.EQ), EXACT_SORT_PROJECT),
            field(tenant, LogicalFieldType.Text, setOf(PredicateOperator.EQ), setOf(FieldCapability.EXACT)),
            field(state, LogicalFieldType.Object),
            field(
                name,
                LogicalFieldType.Text,
                setOf(
                    PredicateOperator.EQ,
                    PredicateOperator.CONTAINS,
                ),
                setOf(
                    FieldCapability.EXACT,
                    FieldCapability.FULL_TEXT,
                    FieldCapability.LITERAL_PATTERN,
                    FieldCapability.SORTABLE,
                    FieldCapability.PROJECTABLE,
                    FieldCapability.AGGREGATABLE,
                ),
            ),
            field(
                items,
                LogicalFieldType.Array(
                    LogicalFieldType.Object,
                    Nullability.NON_NULL,
                    EmptyArraySemantics.DISTINCT,
                ),
                capabilities = setOf(FieldCapability.ELEMENT_MATCH),
            ),
            field(itemName, LogicalFieldType.Text, setOf(PredicateOperator.EQ), setOf(FieldCapability.EXACT)),
        ),
        listOf(QuerySearchScopeDefinition(scope, null, listOf(name), listOf(name))),
    )
    private val searchBinding = ElasticsearchSearchScopeBinding(scope, mapOf(name to "state.name"))
    private val binding = ElasticsearchSnapshotQueryBinding(
        schema,
        INDEX,
        VERSION,
        linkedMapOf(
            identity to ElasticsearchFieldBinding(
                MessageRecords.AGGREGATE_ID,
                EXACT_SORT_PROJECT,
                exactField = "_id",
                sortField = MessageRecords.AGGREGATE_ID,
                keywordReadiness = KEYWORD_READINESS,
            ),
            tenant to ElasticsearchFieldBinding(
                MessageRecords.TENANT_ID,
                setOf(FieldCapability.EXACT),
                exactField = MessageRecords.TENANT_ID,
                keywordReadiness = KEYWORD_READINESS,
            ),
            state to ElasticsearchFieldBinding("state", emptySet()),
            name to ElasticsearchFieldBinding(
                "state.name",
                setOf(
                    FieldCapability.EXACT,
                    FieldCapability.FULL_TEXT,
                    FieldCapability.LITERAL_PATTERN,
                    FieldCapability.SORTABLE,
                    FieldCapability.PROJECTABLE,
                    FieldCapability.AGGREGATABLE,
                ),
                exactField = "state.name.exact",
                searchField = "state.name",
                searchAnalyzer = "standard",
                literalField = "state.name.exact",
                sortField = "state.name.exact",
                groupField = "state.name.exact",
                groupReadiness = GROUP_READINESS,
                keywordReadiness = KEYWORD_READINESS,
            ),
            items to ElasticsearchFieldBinding(
                "state.items",
                setOf(FieldCapability.ELEMENT_MATCH),
                nestedPath = "state.items",
            ),
            itemName to ElasticsearchFieldBinding(
                "state.items.name",
                setOf(FieldCapability.EXACT),
                exactField = "state.items.name",
                keywordReadiness = KEYWORD_READINESS,
            ),
        ),
        listOf(searchBinding),
    )

    private fun field(
        id: QueryFieldId,
        type: LogicalFieldType,
        operators: Set<PredicateOperator> = emptySet(),
        capabilities: Set<FieldCapability> = emptySet(),
    ) = QueryFieldSchema(id, type, Presence.OPTIONAL, Nullability.NULLABLE, operators, capabilities)

    private companion object {
        const val INDEX = "wow.sales.order.snapshot"
        const val VERSION = "order-query-v1"
        const val MAPPING_VERSION_META = "wow_query_mapping_version"
        const val DOCUMENT_KIND_META = "wow_query_document_kind"
        const val SCHEMA_CONTRACT_META = "wow_query_schema_contract_id"
        const val CAPABILITY_DIGEST_META = "wow_query_capability_digest"
        val KEYWORD_READINESS = ElasticsearchKeywordReadiness(128, 512, true, true)
        val GROUP_READINESS = ElasticsearchGroupReadiness(historicalValuesAudited = true)
        val EXACT_SORT_PROJECT = setOf(
            FieldCapability.EXACT,
            FieldCapability.SORTABLE,
            FieldCapability.PROJECTABLE,
        )
    }
}
