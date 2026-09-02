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

import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryCompatibilityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.LogicalQueryFieldSchema
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryRewriteMode
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
import kotlin.reflect.jvm.javaField

@Suppress("LargeClass")
class ElasticsearchQuerySchemaAdapterTest {
    @Test
    fun `binding should retain a logical mask rule and reject every physical multi-field`() {
        val secret = QueryField("state.secret")
        val rule = fullMaskRule()

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            LogicalQuerySchema(mapOf(secret to field(QueryValueType.STRING, maskRule = rule))),
            ElasticsearchIndexMapping.from(
                INDEX,
                TypeMapping.of { mapping ->
                    mapping.properties(secret.path) { property ->
                        property.text { text ->
                            text.fields("keyword") { it.keyword { keyword -> keyword } }
                                .fields("raw") { it.keyword { keyword -> keyword } }
                        }
                    }
                },
            ),
        )

        schema.fields.getValue(secret).masked.assert().isTrue()
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("${secret.path}.raw"), "secret")),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        schema.resolve(query).compatibility.assert()
            .isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `event stream schema should retain model and nested body capability`() {
        val body = QueryField("body")
        val name = QueryField("body.name")
        val logical = LogicalQuerySchema(
            linkedMapOf(
                body to field(QueryValueType.OBJECT, QueryCardinality.MANY),
                name to field(QueryValueType.STRING),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("body") { property ->
                property.nested { nested ->
                    nested.properties("name") { it.keyword { keyword -> keyword } }
                }
            }
        }
        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
            QueryModel.EVENT_STREAM,
        )

        schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM)
        schema.fields.getValue(body).let { fieldSchema ->
            fieldSchema.bindings.assert().containsKey(QueryCapability.ELEMENT_SCOPE)
            fieldSchema.rewriteMode.assert().isEqualTo(QueryRewriteMode.INFER)
        }
        schema.fields.getValue(name).bindings.assert().containsKey(QueryCapability.AGGREGATE_TERMS)
        schema.rewriteMode.assert().isEqualTo(QueryRewriteMode.INFER)
    }

    @Test
    fun `nested-only search fields should not advertise root model search`() {
        val child = QueryField("state.orders.note")
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("state.orders") to field(QueryValueType.OBJECT, QueryCardinality.MANY),
                child to field(QueryValueType.STRING),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("state.orders") { orders ->
                orders.nested { nested ->
                    nested.properties("note") { note -> note.text { it } }
                }
            }
        }
        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.capabilities.assert()
            .doesNotContain(QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE)
        schema.fields.getValue(child).bindings.assert().containsKey(QueryCapability.FULL_TEXT_TERMS)
        schema.resolve(SearchFilter("note")).compatibility.assert()
            .isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `declared flattened string descendants should support exact matching and projection`() {
        val field = QueryField("state.labels.color")
        val schema = ElasticsearchQuerySchemaAdapter.bind(
            LogicalQuerySchema(mapOf(field to field(QueryValueType.STRING))),
            ElasticsearchIndexMapping.from(
                INDEX,
                TypeMapping.of { mapping ->
                    mapping.properties("state.labels") { labels -> labels.flattened { it } }
                },
            ),
        )

        schema.binding(field.path, QueryCapability.EXACT_MATCH).assertPath(field.path, "flattened")
        val projection = Projection(include = listOf(field))
        schema.resolve(ListQuery(MatchAllFilter, projection = projection)).let { resolved ->
            resolved.value.projection.assert().isSameAs(projection)
            resolved.compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
        }
    }

    @Test
    fun `declared unmapped source fields should retain projection while runtime fields do not`() {
        val source = QueryField("state.opaque.name")
        val runtime = QueryField("state.runtimeCode")
        val schema = ElasticsearchQuerySchemaAdapter.bind(
            LogicalQuerySchema(
                linkedMapOf(
                    source to field(QueryValueType.STRING),
                    runtime to field(QueryValueType.STRING),
                ),
            ),
            ElasticsearchIndexMapping.from(
                INDEX,
                TypeMapping.of { mapping ->
                    mapping.properties("state.opaque") { it.`object` { field -> field.enabled(false) } }
                        .runtime(runtime.path) { it.type(RuntimeFieldType.Keyword) }
                },
            ),
        )

        schema.fields.getValue(source).projectionField.assert().isEqualTo(source)
        schema.fields.getValue(runtime).projectionField.assert().isNull()
        schema.resolve(ListQuery(MatchAllFilter, projection = Projection(include = listOf(source))))
            .compatibility.assert()
            .isEqualTo(QueryCompatibilityLevel.EXACT)
    }

    @Test
    fun `projection should retain the source path when presence uses a multi-field`() {
        val field = QueryField("state.name")
        val logical = LogicalQuerySchema(mapOf(field to field(QueryValueType.STRING)))
        val mapping = TypeMapping.of { type ->
            type.properties(field.path) { property ->
                property.text { text ->
                    text.index(false).fields("keyword") { multiField -> multiField.keyword { it } }
                }
            }
        }
        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.binding(field.path, QueryCapability.PRESENCE)
            .assertPath("${field.path}.keyword", "keyword")
        schema.fields.getValue(field).rewriteMode.assert().isEqualTo(QueryRewriteMode.REQUIRED)
        val projection = Projection(include = listOf(field))
        schema.resolve(ListQuery(MatchAllFilter, projection = projection)).let { resolved ->
            resolved.value.projection.assert().isSameAs(projection)
            resolved.compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
        }
    }

    @Test
    fun `metadata sort fields should be exact backend bindings`() {
        val schema = ElasticsearchQuerySchemaAdapter.bind(
            LogicalQuerySchema(emptyMap()),
            ElasticsearchIndexMapping.from(INDEX, TypeMapping.of { it }),
        )
        val sort = listOf("_score", "_doc", "_shard_doc").map { Sort(QueryField(it), Sort.Direction.ASC) }

        schema.resolve(ListQuery(MatchAllFilter, sort = sort)).let { resolved ->
            resolved.value.sort.assert().isSameAs(sort)
            resolved.compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
        }
        listOf(
            EqualFilter(QueryField("_id"), tools.jackson.databind.node.StringNode.valueOf("id")),
            InFilter(
                QueryField("_id"),
                listOf(tools.jackson.databind.node.StringNode.valueOf("id")),
            ),
        ).forEach { filter ->
            schema.resolve(filter).let { resolved ->
                resolved.value.assert().isEqualTo(filter)
                resolved.compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
            }
        }
    }

    @Test
    fun `known scalar mappings should intersect with logical value types`() {
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("state.keywordInteger") to field(QueryValueType.INTEGER),
                QueryField("state.numericString") to field(QueryValueType.STRING),
                QueryField("state.floatingInteger") to field(QueryValueType.INTEGER),
                QueryField("state.integer") to field(QueryValueType.INTEGER),
                QueryField("state.decimal") to field(QueryValueType.DECIMAL),
                QueryField("state.boolean") to field(QueryValueType.BOOLEAN),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("state.keywordInteger") { it.keyword { keyword -> keyword } }
                .properties("state.numericString") { it.long_ { number -> number } }
                .properties("state.floatingInteger") { it.double_ { number -> number } }
                .properties("state.integer") { it.long_ { number -> number } }
                .properties("state.decimal") { it.double_ { number -> number } }
                .properties("state.boolean") { it.boolean_ { boolean -> boolean } }
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.bindings("state.keywordInteger").assert().containsExactly(QueryCapability.PRESENCE)
        schema.bindings("state.numericString").assert().containsExactly(QueryCapability.PRESENCE)
        schema.bindings("state.floatingInteger").assert().containsExactly(QueryCapability.PRESENCE)
        schema.bindings("state.integer").assert().contains(
            QueryCapability.EXACT_MATCH,
            QueryCapability.RANGE,
            QueryCapability.SORT,
            QueryCapability.AGGREGATE_TERMS,
            QueryCapability.AGGREGATE_NUMERIC,
        )
        schema.bindings("state.decimal").assert().contains(
            QueryCapability.EXACT_MATCH,
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_NUMERIC,
        )
        schema.bindings("state.boolean").assert().contains(
            QueryCapability.EXACT_MATCH,
            QueryCapability.SORT,
            QueryCapability.AGGREGATE_TERMS,
        )
    }

    @Test
    fun `fielddata text should support terms aggregation`() {
        val field = QueryField("state.category")
        val schema = ElasticsearchQuerySchemaAdapter.bind(
            LogicalQuerySchema(mapOf(field to field(QueryValueType.STRING))),
            ElasticsearchIndexMapping.from(
                INDEX,
                TypeMapping.of { mapping ->
                    mapping.properties(field.path) { it.text { text -> text.fielddata(true) } }
                },
            ),
        )

        schema.binding(field.path, QueryCapability.AGGREGATE_TERMS).assertPath(field.path, "text")
    }

    @Test
    fun `keyword ip and version mappings should support native string operations`() {
        val keyword = QueryField("state.keyword")
        val ip = QueryField("state.ip")
        val version = QueryField("state.version")
        val schema = ElasticsearchQuerySchemaAdapter.bind(
            LogicalQuerySchema(
                linkedMapOf(
                    keyword to field(QueryValueType.STRING),
                    ip to field(QueryValueType.STRING),
                    version to field(QueryValueType.STRING),
                ),
            ),
            ElasticsearchIndexMapping.from(
                INDEX,
                TypeMapping.of { mapping ->
                    mapping.properties(keyword.path) { it.keyword { field -> field } }
                        .properties(ip.path) { it.ip { field -> field } }
                        .properties(version.path) { it.version { field -> field } }
                },
            ),
        )

        listOf(keyword, ip, version).forEach { field ->
            schema.bindings(field.path).assert().contains(
                QueryCapability.EXACT_MATCH,
                QueryCapability.SORT,
                QueryCapability.AGGREGATE_TERMS,
            )
        }
        listOf(keyword, ip).forEach { field ->
            schema.bindings(field.path).assert().contains(QueryCapability.RANGE)
        }
    }

    @Test
    fun `mixed logical unions should not borrow a capability producer`() {
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("state.keyword") to field(QueryValueType.STRING).copy(
                    valueTypes = setOf(QueryValueType.STRING, QueryValueType.INTEGER),
                ),
                QueryField("state.integer") to field(QueryValueType.STRING).copy(
                    valueTypes = setOf(QueryValueType.STRING, QueryValueType.INTEGER),
                ),
                QueryField("state.numericUnion") to field(QueryValueType.INTEGER).copy(
                    valueTypes = setOf(QueryValueType.INTEGER, QueryValueType.DECIMAL),
                ),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("state.keyword") { it.keyword { keyword -> keyword } }
                .properties("state.integer") { it.long_ { number -> number } }
                .properties("state.numericUnion") { it.long_ { number -> number } }
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.bindings("state.keyword").assert()
            .contains(QueryCapability.PRESENCE, QueryCapability.LITERAL_MATCH)
            .doesNotContain(
                QueryCapability.EXACT_MATCH,
                QueryCapability.SORT,
                QueryCapability.AGGREGATE_TERMS,
                QueryCapability.RANGE,
                QueryCapability.AGGREGATE_NUMERIC,
            )
        schema.bindings("state.integer").assert()
            .contains(QueryCapability.PRESENCE, QueryCapability.RANGE, QueryCapability.AGGREGATE_NUMERIC)
            .doesNotContain(
                QueryCapability.EXACT_MATCH,
                QueryCapability.LITERAL_MATCH,
                QueryCapability.SORT,
                QueryCapability.AGGREGATE_TERMS,
            )
        schema.bindings("state.numericUnion").assert().contains(
            QueryCapability.EXACT_MATCH,
            QueryCapability.RANGE,
            QueryCapability.SORT,
            QueryCapability.AGGREGATE_TERMS,
            QueryCapability.AGGREGATE_NUMERIC,
        )
    }

    @Test
    fun `temporal mappings should require their matching native or integral kinds`() {
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("state.nativeDate") to field(QueryValueType.STRING, semanticType = Temporal.Date),
                QueryField("state.nativeDateOnKeyword") to field(
                    QueryValueType.STRING,
                    semanticType = Temporal.Date,
                ),
                QueryField("state.nativeDateWithInteger") to field(
                    QueryValueType.INTEGER,
                    semanticType = Temporal.Date,
                ),
                QueryField("state.epoch") to field(
                    QueryValueType.INTEGER,
                    semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
                ),
                QueryField("state.epochOnDouble") to field(
                    QueryValueType.INTEGER,
                    semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
                ),
                QueryField("state.epochWithString") to field(
                    QueryValueType.STRING,
                    semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
                ),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("state.nativeDate") { it.dateNanos { date -> date } }
                .properties("state.nativeDateOnKeyword") { it.keyword { keyword -> keyword } }
                .properties("state.nativeDateWithInteger") { it.date { date -> date } }
                .properties("state.epoch") { it.long_ { number -> number } }
                .properties("state.epochOnDouble") { it.double_ { number -> number } }
                .properties("state.epochWithString") { it.long_ { number -> number } }
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.bindings("state.nativeDate").assert().contains(
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_TEMPORAL,
        )
        schema.bindings("state.nativeDateOnKeyword").assert().containsExactly(QueryCapability.PRESENCE)
        schema.bindings("state.nativeDateWithInteger").assert().containsExactly(QueryCapability.PRESENCE)
        schema.bindings("state.epoch").assert().contains(
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_NUMERIC,
            QueryCapability.AGGREGATE_TEMPORAL,
        )
        schema.bindings("state.epochOnDouble").assert().containsExactly(QueryCapability.PRESENCE)
        schema.bindings("state.epochWithString").assert().containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `unsigned long should not prove epoch while signed runtime and ordinary numeric remain valid`() {
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("state.epoch") to field(
                    QueryValueType.INTEGER,
                    semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
                ),
                QueryField("state.integer") to field(QueryValueType.INTEGER),
                QueryField("state.runtimeEpoch") to field(
                    QueryValueType.INTEGER,
                    semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS),
                ),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("state.epoch") { it.unsignedLong { number -> number } }
                .properties("state.integer") { it.unsignedLong { number -> number } }
                .runtime("state.runtimeEpoch") { it.type(RuntimeFieldType.Long) }
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.bindings("state.epoch").assert().containsExactly(QueryCapability.PRESENCE)
        schema.bindings("state.integer").assert().contains(
            QueryCapability.EXACT_MATCH,
            QueryCapability.RANGE,
            QueryCapability.SORT,
            QueryCapability.AGGREGATE_TERMS,
            QueryCapability.AGGREGATE_NUMERIC,
        )
        schema.bindings("state.runtimeEpoch").assert().contains(QueryCapability.AGGREGATE_TEMPORAL)
    }

    @Test
    fun `runtime aliases and multifields should retain logical type checks`() {
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("state.aliasInteger") to field(QueryValueType.INTEGER),
                QueryField("state.multifieldInteger") to field(QueryValueType.INTEGER),
                QueryField("state.runtimeInteger") to field(QueryValueType.INTEGER),
                QueryField("state.runtimeString") to field(QueryValueType.STRING),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("state.target") { it.keyword { keyword -> keyword } }
                .properties("state.aliasInteger") { it.alias { alias -> alias.path("state.target") } }
                .properties("state.multifieldInteger") { field ->
                    field.text { text -> text.fields("keyword") { it.keyword { keyword -> keyword } } }
                }.runtime("state.runtimeInteger") { it.type(RuntimeFieldType.Keyword) }
                .runtime("state.runtimeString") { it.type(RuntimeFieldType.Long) }
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.bindings("state.aliasInteger").assert().containsExactly(QueryCapability.PRESENCE)
        schema.bindings("state.multifieldInteger").assert().containsExactly(QueryCapability.PRESENCE)
        schema.bindings("state.runtimeInteger").assert().containsExactly(QueryCapability.PRESENCE)
        schema.bindings("state.runtimeString").assert().containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `element scope should require a many object on a nested mapping`() {
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("state.items") to field(QueryValueType.OBJECT, QueryCardinality.MANY),
                QueryField("state.singleObject") to field(QueryValueType.OBJECT),
                QueryField("state.stringItems") to field(QueryValueType.STRING, QueryCardinality.MANY),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("state.items") { it.nested { nested -> nested } }
                .properties("state.singleObject") { it.nested { nested -> nested } }
                .properties("state.stringItems") { it.nested { nested -> nested } }
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.bindings("state.items").assert().contains(QueryCapability.ELEMENT_SCOPE)
        schema.bindings("state.singleObject").assert().doesNotContain(QueryCapability.ELEMENT_SCOPE)
        schema.bindings("state.stringItems").assert().doesNotContain(QueryCapability.ELEMENT_SCOPE)
    }

    @Test
    fun `object and nested containers should not expose presence`() {
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("state") to field(QueryValueType.OBJECT),
                QueryField("state.orders") to field(QueryValueType.OBJECT, QueryCardinality.MANY),
                QueryField("state.orders.status") to field(QueryValueType.STRING),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("state") { state ->
                state.`object` { objectField ->
                    objectField.properties("orders") { orders ->
                        orders.nested { nested ->
                            nested.properties("status") { status -> status.keyword { it } }
                        }
                    }
                }
            }
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.bindings("state").assert().doesNotContain(QueryCapability.PRESENCE)
        schema.bindings("state.orders").assert().containsExactly(QueryCapability.ELEMENT_SCOPE)
        schema.bindings("state.orders.status").assert().contains(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.SORT,
            QueryCapability.AGGREGATE_TERMS,
        )
    }

    @Test
    fun `dynamic fields should expose no inheritable bindings`() {
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("tags") to field(QueryValueType.OBJECT, dynamicChildren = true),
                QueryField("state.labels") to field(QueryValueType.OBJECT, dynamicChildren = true),
                QueryField("state.blocked") to field(QueryValueType.OBJECT, dynamicChildren = true),
                QueryField("state.strict") to field(QueryValueType.OBJECT, dynamicChildren = true),
                QueryField("state.disabled") to field(QueryValueType.OBJECT, dynamicChildren = true),
                QueryField("state.unindexed") to field(QueryValueType.OBJECT, dynamicChildren = true),
                QueryField("state.ordinary") to field(QueryValueType.OBJECT),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("tags") { it.`object` { objectField -> objectField.dynamic(DynamicMapping.True) } }
                .properties("state.labels") { it.flattened { flattened -> flattened } }
                .properties("state.blocked") {
                    it.`object` { objectField -> objectField.dynamic(DynamicMapping.False) }
                }.properties("state.strict") {
                    it.`object` { objectField -> objectField.dynamic(DynamicMapping.Strict) }
                }.properties("state.disabled") {
                    it.`object` { objectField -> objectField.enabled(false) }
                }.properties("state.unindexed") {
                    it.flattened { flattened -> flattened.index(false) }
                }.properties("state.ordinary") { it.flattened { flattened -> flattened } }
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.bindings("tags").assert().isEmpty()
        listOf(
            "tags",
            "state.labels",
            "state.blocked",
            "state.strict",
            "state.disabled",
            "state.unindexed",
        ).forEach { path ->
            schema.fields.getValue(QueryField(path)).dynamicChildren.assert().isFalse()
        }
        schema.field(QueryField("tags.department")).assert().isNull()
        schema.bindings("state.labels").assert().isEmpty()
        schema.bindings("state.blocked").assert().isEmpty()
        schema.bindings("state.strict").assert().isEmpty()
        schema.bindings("state.disabled").assert().isEmpty()
        schema.bindings("state.unindexed").assert().isEmpty()
        schema.bindings("state.ordinary").assert().doesNotContain(QueryCapability.EXACT_MATCH)
    }

    @Test
    fun `dynamic nested field should retain only element scope`() {
        val logical = LogicalQuerySchema(
            mapOf(
                QueryField("state.items") to field(
                    QueryValueType.OBJECT,
                    QueryCardinality.MANY,
                    dynamicChildren = true,
                ),
            ),
        )
        val mapping = TypeMapping.of { type ->
            type.properties("state.items") { it.nested { nested -> nested.dynamic(DynamicMapping.True) } }
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(logical, ElasticsearchIndexMapping.from(INDEX, mapping))

        schema.bindings("state.items").assert().containsExactly(QueryCapability.ELEMENT_SCOPE)
        schema.fields.getValue(QueryField("state.items")).dynamicChildren.assert().isFalse()
        schema.field(QueryField("state.items.unknown")).assert().isNull()
    }

    @Test
    fun `invalid nested parents should suppress every descendant binding`() {
        val logical = LogicalQuerySchema(
            linkedMapOf(
                QueryField("state.single") to field(QueryValueType.OBJECT),
                QueryField("state.single.status") to field(QueryValueType.STRING),
                QueryField("state.strings") to field(QueryValueType.STRING, QueryCardinality.MANY),
                QueryField("state.strings.status") to field(QueryValueType.STRING),
                QueryField("state.items") to field(QueryValueType.OBJECT, QueryCardinality.MANY),
                QueryField("state.items.status") to field(QueryValueType.STRING),
            ),
        )
        val mapping = TypeMapping.of { type ->
            listOf("state.single", "state.strings", "state.items").forEach { path ->
                type.properties(path) { property ->
                    property.nested { nested ->
                        nested.properties("status") { status -> status.keyword { it } }
                    }
                }
            }
            type
        }

        val schema = ElasticsearchQuerySchemaAdapter.bind(
            logical,
            ElasticsearchIndexMapping.from(INDEX, mapping),
        )

        schema.bindings("state.single").assert().doesNotContain(QueryCapability.ELEMENT_SCOPE)
        schema.bindings("state.single.status").assert().isEmpty()
        schema.bindings("state.strings").assert().doesNotContain(QueryCapability.ELEMENT_SCOPE)
        schema.bindings("state.strings.status").assert().isEmpty()
        schema.bindings("state.items").assert().contains(QueryCapability.ELEMENT_SCOPE)
        schema.bindings("state.items.status").assert().contains(
            QueryCapability.EXACT_MATCH,
            QueryCapability.SORT,
        )
    }

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
            mapOf(QueryField("state.semantic") to field(QueryValueType.STRING)),
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
        schema.fields.getValue(QueryField("state.semantic")).bindings.assert()
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
        schema.fields.getValue(QueryField("state.name")).rewriteMode.assert().isEqualTo(QueryRewriteMode.INFER)

        schema.binding("state.score", QueryCapability.RANGE).assertPath("state.score", "double")
        schema.binding("state.score", QueryCapability.AGGREGATE_NUMERIC).assertPath("state.score", "double")
        schema.binding("state.createdAt", QueryCapability.AGGREGATE_TEMPORAL)
            .assertPath("state.createdAt", "date")
        schema.fields.getValue(QueryField("state.createdAt")).rewriteMode.assert().isEqualTo(QueryRewriteMode.INFER)
        schema.binding("state.createdNanos", QueryCapability.AGGREGATE_TEMPORAL)
            .assertPath("state.createdNanos", "date_nanos")
        schema.binding("state.epoch", QueryCapability.AGGREGATE_TEMPORAL).assertPath("state.epoch", "long")
        schema.binding("state.formatted", QueryCapability.RANGE).assertPath("state.formatted", "keyword")
        schema.fields.getValue(QueryField("state.formatted")).bindings.assert()
            .doesNotContainKey(QueryCapability.AGGREGATE_TEMPORAL)

        schema.binding("state.items", QueryCapability.ELEMENT_SCOPE).assertPath("state.items", "nested")
        schema.field(QueryField("state.labels.release")).assert().isNull()
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
        val aliasProjection = Projection(include = listOf(QueryField("state.nameAlias")))
        schema.resolve(ListQuery(MatchAllFilter, projection = aliasProjection))
            .let { resolved ->
                resolved.value.projection.assert().isSameAs(aliasProjection)
                resolved.compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
            }
        schema.resolve(
            ListQuery(
                MatchAllFilter,
                projection = Projection(include = listOf(QueryField("state.runtimeCode"))),
            ),
        )
            .compatibility.assert()
            .isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)

        schema.fields.getValue(QueryField("state.ambiguous")).bindings.assert()
            .doesNotContainKey(QueryCapability.EXACT_MATCH)
            .doesNotContainKey(QueryCapability.SORT)
        schema.fields.getValue(QueryField("state.unindexedText")).bindings.assert()
            .doesNotContainKey(QueryCapability.FULL_TEXT_TERMS)
        schema.fields.getValue(QueryField("state.unindexedCode")).bindings.assert()
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
                QueryField("state.name") to QueryFieldDeclaration(
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
                schema.binding("state.name", QueryCapability.FULL_TEXT_TERMS).physicalField.assert()
                    .isEqualTo(QueryField("state.name"))
            }.verifyComplete()
        val refreshed = provider.refresh().block()!!
        refreshed.binding("state.name", QueryCapability.EXACT_MATCH).physicalField.assert()
            .isEqualTo(QueryField("state.name"))
        refreshed.fields.getValue(QueryField("state.name")).bindings.assert()
            .doesNotContainKey(QueryCapability.FULL_TEXT_TERMS)
        provider.schema().block().assert().isSameAs(refreshed)
    }

    private fun me.ahoo.wow.query.schema.QueryModelSchema.binding(
        field: String,
        capability: QueryCapability,
    ) = fields.getValue(QueryField(field)).bindings.getValue(capability)

    private fun me.ahoo.wow.query.schema.QueryModelSchema.bindings(field: String) =
        fields.getValue(QueryField(field)).bindings.keys

    private fun me.ahoo.wow.query.schema.QueryFieldBinding.assertPath(path: String, storageType: String) {
        resolvedField.assert().isEqualTo(QueryField(path))
        physicalField.assert().isEqualTo(QueryField(path))
        this.storageType?.value.assert().isEqualTo(storageType)
    }

    private fun logicalSchema() = LogicalQuerySchema(
        linkedMapOf(
            QueryField("state.name") to field(QueryValueType.STRING),
            QueryField("state.code") to field(QueryValueType.STRING),
            QueryField("state.score") to field(QueryValueType.DECIMAL),
            QueryField("state.createdAt") to field(QueryValueType.STRING, semanticType = Temporal.Date),
            QueryField("state.createdNanos") to field(QueryValueType.STRING, semanticType = Temporal.Date),
            QueryField("state.epoch") to field(
                QueryValueType.INTEGER,
                semanticType = Temporal.Epoch(TimeUnit.MICROSECONDS),
            ),
            QueryField("state.formatted") to field(
                QueryValueType.STRING,
                semanticType = Temporal.Formatted("yyyy-MM-dd"),
            ),
            QueryField("state.items") to field(QueryValueType.OBJECT, QueryCardinality.MANY),
            QueryField("state.labels") to field(QueryValueType.OBJECT, dynamicChildren = true),
            QueryField("state.ambiguous") to field(QueryValueType.STRING),
            QueryField("state.unindexedText") to field(QueryValueType.STRING),
            QueryField("state.unindexedCode") to field(QueryValueType.STRING),
            QueryField("state.nameAlias") to field(QueryValueType.STRING),
            QueryField("state.codeAlias") to field(QueryValueType.STRING),
            QueryField("state.runtimeCode") to field(QueryValueType.STRING),
            QueryField("state.runtimeScore") to field(QueryValueType.DECIMAL),
            QueryField("state.runtimeAt") to field(QueryValueType.STRING, semanticType = Temporal.Date),
            QueryField("state.runtime.code") to field(QueryValueType.STRING),
        ),
    )

    private fun field(
        valueType: QueryValueType,
        cardinality: QueryCardinality = QueryCardinality.SINGLE,
        semanticType: Temporal? = null,
        dynamicChildren: Boolean = false,
        maskRule: MaskRule? = null,
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
        maskRule = maskRule,
    )

    private fun fullMaskRule(): MaskRule {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private data class Masked(@field:Mask val secret: String)

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
