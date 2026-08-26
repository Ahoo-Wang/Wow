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

package me.ahoo.wow.elasticsearch.query.schema

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.LogicalQueryFieldSchema
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.TimeUnit

class ElasticsearchQuerySchemaAdapterTest {
    @Test
    fun `adapter should wrap mapping failures with their cause`() {
        val failure = IllegalStateException("mapping unavailable")
        val resolver = mockk<ElasticsearchIndexMappingResolver> {
            every { currentOrLoad(INDEX) } returns Mono.error(failure)
        }

        ElasticsearchQuerySchemaAdapter(INDEX, resolver).resolve(logicalSchema()).test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(QuerySchemaUnavailableException::class.java)
                error.cause.assert().isSameAs(failure)
            }.verify()
    }

    @Test
    fun `semantic text should support terms but not phrase search`() {
        val logical = LogicalQuerySchema(
            mapOf(LogicalField("state.semantic") to field(QueryValueType.STRING)),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("state.semantic") { it.semanticText { semantic -> semantic } }
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.capabilities.assert()
            .contains(QueryCapability.FULL_TEXT_TERMS)
            .doesNotContain(QueryCapability.FULL_TEXT_PHRASE)
        schema.fields.getValue(LogicalField("state.semantic")).bindings.assert()
            .containsKey(QueryCapability.FULL_TEXT_TERMS)
            .doesNotContainKey(QueryCapability.FULL_TEXT_PHRASE)
    }

    @Test
    fun `bind should expose mapped capabilities through the narrowest physical fields`() {
        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logicalSchema(),
            ElasticsearchIndexMapping.from(INDEX, mapping()),
        )

        schema.capabilities.assert().containsExactlyInAnyOrder(
            QueryCapability.FULL_TEXT_TERMS,
            QueryCapability.FULL_TEXT_PHRASE,
        )
        schema.binding("state.name", QueryCapability.FULL_TEXT_TERMS).assertPath("state.name", "text")
        schema.binding("state.name", QueryCapability.FULL_TEXT_PHRASE).assertPath("state.name", "text")
        schema.binding("state.name", QueryCapability.EXACT_MATCH).assertPath("state.name.keyword", "keyword")
        schema.binding("state.name", QueryCapability.LITERAL_MATCH).assertPath("state.name.keyword", "keyword")
        schema.binding("state.name", QueryCapability.SORT).assertPath("state.name.keyword", "keyword")
        schema.binding("state.name", QueryCapability.AGGREGATE_TERMS).assertPath("state.name.keyword", "keyword")

        schema.binding("state.score", QueryCapability.RANGE).assertPath("state.score", "double")
        schema.binding("state.score", QueryCapability.AGGREGATE_NUMERIC).assertPath("state.score", "double")
        schema.binding("state.createdAt", QueryCapability.AGGREGATE_TEMPORAL)
            .assertPath("state.createdAt", "date")
        schema.binding("state.createdNanos", QueryCapability.AGGREGATE_TEMPORAL)
            .assertPath("state.createdNanos", "date_nanos")
        schema.binding("state.epoch", QueryCapability.AGGREGATE_TEMPORAL).assertPath("state.epoch", "long")
        schema.fields.getValue(LogicalField("state.formatted")).bindings.assert()
            .doesNotContainKey(QueryCapability.AGGREGATE_TEMPORAL)

        schema.binding("state.items", QueryCapability.ELEMENT_SCOPE).assertPath("state.items", "nested")
        schema.resolve(LogicalField("state.labels.release"))!!
            .bindings.getValue(QueryCapability.EXACT_MATCH).assertPath("state.labels.release", "flattened")
    }

    @Test
    fun `bind should preserve aliases runtime fields and reject unsupported or ambiguous paths`() {
        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logicalSchema(),
            ElasticsearchIndexMapping.from(INDEX, mapping()),
        )

        schema.binding("state.nameAlias", QueryCapability.FULL_TEXT_TERMS).assertPath("state.nameAlias", "text")
        schema.binding("state.codeAlias", QueryCapability.EXACT_MATCH).assertPath("state.codeAlias", "keyword")
        schema.binding("state.runtimeCode", QueryCapability.LITERAL_MATCH)
            .assertPath("state.runtimeCode", "keyword")
        schema.binding("state.runtimeScore", QueryCapability.AGGREGATE_NUMERIC)
            .assertPath("state.runtimeScore", "double")
        schema.binding("state.runtimeAt", QueryCapability.AGGREGATE_TEMPORAL)
            .assertPath("state.runtimeAt", "date")
        schema.binding("state.runtime.code", QueryCapability.AGGREGATE_TERMS)
            .assertPath("state.runtime.code", "keyword")

        schema.fields.getValue(LogicalField("state.ambiguous")).bindings.assert()
            .doesNotContainKey(QueryCapability.EXACT_MATCH)
            .doesNotContainKey(QueryCapability.SORT)
        schema.fields.getValue(LogicalField("state.unindexedText")).bindings.assert()
            .doesNotContainKey(QueryCapability.FULL_TEXT_TERMS)
        schema.fields.getValue(LogicalField("state.unindexedCode")).bindings.assert()
            .doesNotContainKey(QueryCapability.LITERAL_MATCH)
            .containsKeys(QueryCapability.EXACT_MATCH, QueryCapability.SORT, QueryCapability.AGGREGATE_TERMS)
    }

    @Test
    fun `adapter refresh should reload mapping and provider should publish the replacement`() {
        val client = mockk<ReactiveElasticsearchClient>()
        val indices = mockk<ReactiveElasticsearchIndicesClient>()
        every { client.indices() } returns indices
        every { indices.getMapping(any<GetMappingRequest>()) } returnsMany listOf(
            Mono.just(mappingResponse(textName())),
            Mono.just(mappingResponse(keywordName())),
        )
        val context = QuerySchemaContext(MOCK_AGGREGATE_METADATA, me.ahoo.wow.api.query.schema.QueryModel.SNAPSHOT)
        val declaration = QuerySchemaDeclaration(
            mapOf(
                LogicalField("state.name") to QueryFieldDeclaration(
                    valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                ),
            ),
        )
        val provider = DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, declaration)))),
            ElasticsearchQuerySchemaAdapter(INDEX, ElasticsearchIndexMappingResolver(client)),
        )

        provider.schema().test()
            .assertNext { schema ->
                schema.binding("state.name", QueryCapability.FULL_TEXT_TERMS).physicalPath.assert()
                    .isEqualTo("state.name")
            }.verifyComplete()
        val refreshed = provider.refresh().block()!!
        refreshed.binding("state.name", QueryCapability.EXACT_MATCH).physicalPath.assert().isEqualTo("state.name")
        refreshed.fields.getValue(LogicalField("state.name")).bindings.assert()
            .doesNotContainKey(QueryCapability.FULL_TEXT_TERMS)
        provider.schema().block().assert().isSameAs(refreshed)
    }

    private fun me.ahoo.wow.query.schema.QueryModelSchema.binding(
        field: String,
        capability: QueryCapability,
    ) = fields.getValue(LogicalField(field)).bindings.getValue(capability)

    private fun me.ahoo.wow.query.schema.QueryFieldBinding.assertPath(path: String, storageType: String) {
        physicalPath.assert().isEqualTo(path)
        this.storageType?.value.assert().isEqualTo(storageType)
    }

    private fun logicalSchema() = LogicalQuerySchema(
        linkedMapOf(
            LogicalField("state.name") to field(QueryValueType.STRING),
            LogicalField("state.code") to field(QueryValueType.STRING),
            LogicalField("state.score") to field(QueryValueType.DECIMAL),
            LogicalField("state.createdAt") to field(QueryValueType.STRING, semanticType = Temporal.Date),
            LogicalField("state.createdNanos") to field(QueryValueType.STRING, semanticType = Temporal.Date),
            LogicalField("state.epoch") to field(
                QueryValueType.INTEGER,
                semanticType = Temporal.Epoch(TimeUnit.MICROSECONDS),
            ),
            LogicalField("state.formatted") to field(
                QueryValueType.STRING,
                semanticType = Temporal.Formatted("yyyy-MM-dd"),
            ),
            LogicalField("state.items") to field(QueryValueType.OBJECT, QueryCardinality.MANY),
            LogicalField("state.labels") to field(QueryValueType.OBJECT, dynamicChildren = true),
            LogicalField("state.ambiguous") to field(QueryValueType.STRING),
            LogicalField("state.unindexedText") to field(QueryValueType.STRING),
            LogicalField("state.unindexedCode") to field(QueryValueType.STRING),
            LogicalField("state.nameAlias") to field(QueryValueType.STRING),
            LogicalField("state.codeAlias") to field(QueryValueType.STRING),
            LogicalField("state.runtimeCode") to field(QueryValueType.STRING),
            LogicalField("state.runtimeScore") to field(QueryValueType.DECIMAL),
            LogicalField("state.runtimeAt") to field(QueryValueType.STRING, semanticType = Temporal.Date),
            LogicalField("state.runtime.code") to field(QueryValueType.STRING),
        ),
    )

    private fun field(
        valueType: QueryValueType,
        cardinality: QueryCardinality = QueryCardinality.SINGLE,
        semanticType: Temporal? = null,
        dynamicChildren: Boolean = false,
    ) = LogicalQueryFieldSchema(
        title = null,
        description = null,
        enumValues = null,
        valueTypes = setOf(valueType),
        nullable = false,
        required = true,
        cardinality = cardinality,
        semanticType = semanticType,
        dynamicChildren = dynamicChildren,
    )

    private fun mapping(): TypeMapping = TypeMapping.of { mapping ->
        mapping.properties("state") { state ->
            state.`object` { objectField ->
                objectField
                    .properties("name") { it.text { text -> text.fields("keyword") { key -> key.keyword { it } } } }
                    .properties("code") { it.keyword { key -> key } }
                    .properties("score") { it.double_ { number -> number } }
                    .properties("createdAt") { it.date { date -> date } }
                    .properties("createdNanos") { it.dateNanos { date -> date } }
                    .properties("epoch") { it.long_ { number -> number } }
                    .properties("formatted") { it.keyword { key -> key } }
                    .properties("items") { it.nested { nested -> nested } }
                    .properties("labels") { it.flattened { flattened -> flattened } }
                    .properties("ambiguous") { field ->
                        field.text { text ->
                            text.fields("raw") { it.keyword { key -> key } }
                                .fields("normalized") { it.keyword { key -> key } }
                        }
                    }.properties("unindexedText") { it.text { text -> text.index(false) } }
                    .properties("unindexedCode") { it.keyword { key -> key.index(false) } }
                    .properties("nameAlias") { it.alias { alias -> alias.path("state.name") } }
                    .properties("codeAlias") { it.alias { alias -> alias.path("state.code") } }
            }
        }.runtime("state.runtimeCode") { it.type(RuntimeFieldType.Keyword) }
            .runtime("state.runtimeScore") { it.type(RuntimeFieldType.Double) }
            .runtime("state.runtimeAt") { it.type(RuntimeFieldType.Date) }
            .runtime("state.runtime") { runtime ->
                runtime.type(RuntimeFieldType.Composite)
                    .fields("code") { it.type(RuntimeFieldType.Keyword) }
            }
    }

    private fun textName(): TypeMapping = stateName(Property.of { it.text { text -> text } })

    private fun keywordName(): TypeMapping = stateName(Property.of { it.keyword { keyword -> keyword } })

    private fun stateName(name: Property): TypeMapping = TypeMapping.of { mapping ->
        mapping.properties("state") { state ->
            state.`object` { objectField -> objectField.properties("name", name) }
        }
    }

    private fun mappingResponse(mapping: TypeMapping): GetMappingResponse = GetMappingResponse.of { response ->
        response.mappings(INDEX, IndexMappingRecord.of { record -> record.mappings(mapping) })
    }

    companion object {
        private const val INDEX = "wow.catalog.sku.snapshot"
    }
}
