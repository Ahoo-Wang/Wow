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

import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.physicalField
import me.ahoo.wow.query.schema.validateQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class ElasticsearchQuerySchemaAdapterTest {
    @Test
    fun `multifields are native bindings and never logical names`() {
        val definition = logical("name" to scalar(QueryValueType.STRING))
        val schema = bind(
            definition,
            TypeMapping.of { mapping ->
                mapping.properties("name") { it.text { text -> text.fields("keyword") { it.keyword { it } } } }
            }
        )
        schema.definition.assert().isSameAs(definition)
        schema.root.assert().isSameAs(definition.root)
        schema.path("name", QueryCapability.EXACT_MATCH).assert().isEqualTo("name.keyword")
        schema.path("name", QueryCapability.FULL_TEXT_TERMS).assert().isEqualTo("name")
        schema.path("name", QueryCapability.CURSOR_SORT).assert().isEqualTo("name.keyword")
        schema.field(QueryField("name"))!!.projectionField.assert().isEqualTo(QueryField("name"))
        schema.field(QueryField("name.keyword")).assert().isNull()
    }

    @Test
    fun `two map keys specialize only observed mappings without widening the declaration`() {
        val text = scalar(QueryValueType.STRING)
        val definition = logical("names" to objectValue(additional = objectValue(additional = text)))
        val schema = bind(
            definition,
            TypeMapping.of { mapping ->
                mapping.properties("names") {
                    it.`object` { names ->
                        names.properties("en") {
                            it.`object` { en ->
                                en.properties(
                                    "primary"
                                ) { it.text { text -> text.fields("keyword") { it.keyword { it } } } }
                            }
                        }
                    }
                }
            }
        )
        schema.path("names.en.primary", QueryCapability.EXACT_MATCH).assert().isEqualTo("names.en.primary.keyword")
        schema.path("names.en.primary", QueryCapability.SORT).assert().isEqualTo("names.en.primary.keyword")
        schema.field(
            QueryField("names.en.primary")
        )!!.projectionField.assert().isEqualTo(QueryField("names.en.primary"))
        schema.field(QueryField("names.fr.primary"))!!.bindings.assert().isEmpty()
        schema.field(QueryField("names.fr.primary"))!!.responseField.assert().isEqualTo(QueryField("names.fr.primary"))
        schema.field(QueryField("names.en.primary.keyword")).assert().isNull()
        schema.field(QueryField("names"))!!.bindings.assert().isEmpty()
        definition.root.properties.getValue("names").properties.assert().isEmpty()
    }

    @Test
    fun `known map keys preserve named declaration overrides instead of widening the default`() {
        val definition = logical(
            "names" to objectValue(
                properties = mapOf("en" to objectValue(additional = scalar(QueryValueType.INTEGER))),
                additional = objectValue(additional = scalar(QueryValueType.STRING)),
            )
        )
        val schema = bind(
            definition,
            TypeMapping.of {
                it.properties("names.en.primary") { it.text { text -> text.fields("keyword") { it.keyword { it } } } }
            }
        )
        val field = schema.field(QueryField("names.en.primary"))!!
        field.value.valueTypes.assert().isEqualTo(setOf(QueryValueType.INTEGER))
        field.bindings.assert().doesNotContainKey(QueryCapability.EXACT_MATCH)
        field.projectionField.assert().isEqualTo(QueryField("names.en.primary"))
    }

    @Test
    fun `array members have scalar operations but no cursor or primitive element scope`() {
        val schema = bind(
            logical("scores" to array(scalar(QueryValueType.INTEGER))),
            TypeMapping.of {
                it.properties("scores") { it.long_ { it } }
            }
        )
        schema.path("scores", QueryCapability.EXACT_MATCH).assert().isEqualTo("scores")
        schema.path("scores", QueryCapability.RANGE).assert().isEqualTo("scores")
        schema.field(QueryField("scores"))!!.bindings.assert()
            .doesNotContainKey(QueryCapability.CURSOR_SORT).doesNotContainKey(QueryCapability.ELEMENT_SCOPE)
        schema.field(QueryField("scores"))!!.value.valueTypes.assert().isEmpty()
    }

    @Test
    fun `array of arrays never gains scalar or element operations`() {
        val schema = bind(
            logical("scores" to array(array(scalar(QueryValueType.INTEGER)))),
            TypeMapping.of {
                it.properties("scores") { it.long_ { it } }
            }
        )
        schema.field(QueryField("scores"))!!.bindings.assert().doesNotContainKey(QueryCapability.EXACT_MATCH)
        schema.field(QueryField("scores"))!!.bindings.assert().doesNotContainKey(QueryCapability.ELEMENT_SCOPE)
    }

    @Test
    fun `union requires native support for every value branch`() {
        val schema = bind(
            logical(
                "amount" to union(scalar(QueryValueType.INTEGER), scalar(QueryValueType.DECIMAL)),
                "uncertain" to union(scalar(QueryValueType.INTEGER), QueryValueSchema(QueryValueKind.UNKNOWN)),
            ),
            TypeMapping.of {
                it.properties("amount") { it.double_ { it } }.properties("uncertain") { it.long_ { it } }
            }
        )
        // A double mapping cannot promise exact storage for the complete integer domain.
        schema.field(QueryField("uncertain"))!!.bindings.assert().doesNotContainKey(QueryCapability.EXACT_MATCH)
        schema.field(QueryField("amount"))!!.projectionField.assert().isEqualTo(QueryField("amount"))
    }

    @Test
    fun `nested descendants retain relative logical scope and reject root access`() {
        val schema = bind(
            logical("orders" to array(objectValue(mapOf("price" to scalar(QueryValueType.INTEGER))))),
            TypeMapping.of {
                it.properties("orders") {
                    it.nested { nested ->
                        nested.properties("price") { it.long_ { it } }
                    }
                }
            }
        )
        schema.path("orders", QueryCapability.ELEMENT_SCOPE).assert().isEqualTo("orders")
        schema.physicalField(QueryField("price"), QueryCapability.RANGE, QueryField("orders"))
            .assert().isEqualTo(QueryField("orders.price"))
        assertThrows<QuerySchemaValidationException> {
            schema.physicalField(QueryField("orders.price"), QueryCapability.RANGE)
        }
        schema.field(QueryField("orders.price"))!!.bindings.assert().doesNotContainKey(QueryCapability.CURSOR_SORT)
        val pricePath = me.ahoo.wow.query.schema.QueryPathTemplate(
            listOf(
                me.ahoo.wow.query.schema.QueryPathSegment.Property("orders"),
                me.ahoo.wow.query.schema.QueryPathSegment.Item,
                me.ahoo.wow.query.schema.QueryPathSegment.Property("price"),
            )
        )
        schema.bindings.getValue(pricePath).responsePath.assert().isEqualTo(pricePath)
    }

    @Test
    fun `object mapping cannot support nested element scope`() {
        val schema = bind(
            logical("orders" to array(objectValue(mapOf("price" to scalar(QueryValueType.INTEGER))))),
            TypeMapping.of {
                it.properties("orders") {
                    it.`object` { nested ->
                        nested.properties("price") { it.long_ { it } }
                    }
                }
            }
        )
        schema.field(QueryField("orders"))!!.bindings.assert().doesNotContainKey(QueryCapability.ELEMENT_SCOPE)
    }

    @Test
    fun `alias cursor identity uses real target and array target cannot sort cursors`() {
        val schema = bind(
            logical(
                "tags" to array(scalar(QueryValueType.STRING)),
                "tagAlias" to scalar(QueryValueType.STRING),
                "code" to scalar(QueryValueType.STRING),
                "codeAlias" to scalar(QueryValueType.STRING),
            ),
            TypeMapping.of {
                it.properties("tags") { it.keyword { it } }.properties("tagAlias") { it.alias { it.path("tags") } }
                    .properties("code") { it.keyword { it } }.properties("codeAlias") { it.alias { it.path("code") } }
            }
        )
        schema.field(QueryField("tagAlias"))!!.bindings.assert().doesNotContainKey(QueryCapability.CURSOR_SORT)
        schema.path("codeAlias", QueryCapability.CURSOR_SORT).assert().isEqualTo("code")
        schema.field(QueryField("codeAlias"))!!.projectionField.assert().isEqualTo(QueryField("code"))
    }

    @Test
    fun `runtime field has native capability without source`() {
        val schema = bind(
            logical("rank" to scalar(QueryValueType.INTEGER)),
            TypeMapping.of {
                it.runtime("rank") { it.type(RuntimeFieldType.Long) }
            }
        )
        schema.path("rank", QueryCapability.RANGE).assert().isEqualTo("rank")
        schema.field(QueryField("rank"))!!.projectionField.assert().isNull()
    }

    @Test
    fun `temporal array binds items semantic without copying it to container`() {
        val items = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.INTEGER),
            semanticType = Temporal.Epoch(TimeUnit.SECONDS)
        )
        val schema =
            bind(logical("times" to array(items)), TypeMapping.of { it.properties("times") { it.long_ { it } } })
        schema.path("times", QueryCapability.RANGE).assert().isEqualTo("times")
        schema.field(QueryField("times"))!!.value.semanticType.assert().isNull()
        schema.field(QueryField("times"))!!.value.items.assert().isSameAs(items)
    }

    @Test
    fun `unindexed keyword keeps doc value operations while literal and text require indexing`() {
        val schema =
            bind(
                logical("code" to scalar(QueryValueType.STRING), "text" to scalar(QueryValueType.STRING)),
                TypeMapping.of {
                    it.properties(
                        "code"
                    ) { it.keyword { it.index(false) } }.properties("text") { it.text { it.index(false) } }
                }
            )
        schema.path("code", QueryCapability.EXACT_MATCH).assert().isEqualTo("code")
        schema.path("code", QueryCapability.SORT).assert().isEqualTo("code")
        schema.field(QueryField("code"))!!.bindings.assert().doesNotContainKey(QueryCapability.LITERAL_MATCH)
        schema.field(QueryField("text"))!!.bindings.assert().doesNotContainKey(QueryCapability.FULL_TEXT_TERMS)
    }

    @Test
    fun `ambiguous keyword siblings do not pick an arbitrary native target`() {
        val schema = bind(
            logical("name" to scalar(QueryValueType.STRING)),
            TypeMapping.of {
                it.properties("name") {
                    it.text { text ->
                        text.fields("raw") { it.keyword { it } }.fields("normalized") { it.keyword { it } }
                    }
                }
            }
        )
        schema.field(QueryField("name"))!!.bindings.assert().doesNotContainKey(QueryCapability.EXACT_MATCH)
        schema.path("name", QueryCapability.FULL_TEXT_TERMS).assert().isEqualTo("name")
    }

    @Test
    fun `nested only search does not imply root model search`() {
        val schema =
            bind(
                logical("items" to array(objectValue(mapOf("name" to scalar(QueryValueType.STRING))))),
                TypeMapping.of {
                    it.properties("items") { it.nested { it.properties("name") { it.text { it } } } }
                }
            )
        schema.capabilities.assert().doesNotContain(QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE)
        schema.path("items.name", QueryCapability.FULL_TEXT_TERMS).assert().isEqualTo("items.name")
    }

    @Test
    fun `metadata capabilities require explicit logical declaration and never include cursor`() {
        val schema =
            bind(
                logical("_score" to scalar(QueryValueType.DECIMAL), "_id" to scalar(QueryValueType.STRING)),
                TypeMapping.of { it }
            )
        schema.path("_score", QueryCapability.SORT).assert().isEqualTo("_score")
        schema.field(QueryField("_score"))!!.bindings.assert().doesNotContainKey(QueryCapability.CURSOR_SORT)
        schema.field(QueryField("_score"))!!.projectionField.assert().isNull()
        schema.path("_id", QueryCapability.EXACT_MATCH).assert().isEqualTo("_id")
    }

    @Test
    fun `explicit flattened descendant retains native string exact matching`() {
        val schema =
            bind(
                logical("labels" to objectValue(mapOf("color" to scalar(QueryValueType.STRING)))),
                TypeMapping.of {
                    it.properties("labels") { it.flattened { it } }
                }
            )
        schema.path("labels.color", QueryCapability.EXACT_MATCH).assert().isEqualTo("labels.color")
        schema.field(QueryField("labels"))!!.bindings.assert().doesNotContainKey(QueryCapability.EXACT_MATCH)
    }

    @Test
    fun `mask declarations do not trim native capabilities`() {
        val annotation = ProtectedValue::class.java.getDeclaredField("secret")
            .getAnnotation(me.ahoo.wow.api.query.mask.Mask::class.java)
        val rule = me.ahoo.wow.query.schema.MaskRule(
            me.ahoo.wow.api.query.mask.FullMaskStrategy::class,
            annotation,
            me.ahoo.wow.api.query.mask.FullMaskStrategy.compile(annotation)
        )
        val value = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), maskRule = rule)
        val schema =
            bind(
                logical("state" to objectValue(mapOf("secret" to value))),
                TypeMapping.of {
                    it.properties("state.secret") { it.keyword { it } }
                }
            )
        schema.field(QueryField("state.secret"))!!.value.assert().isSameAs(value)
        schema.path("state.secret", QueryCapability.CURSOR_SORT).assert().isEqualTo("state.secret")
        schema.path("state.secret", QueryCapability.AGGREGATE_TERMS).assert().isEqualTo("state.secret")
    }

    private data class ProtectedValue(@field:me.ahoo.wow.api.query.mask.Mask val secret: String)

    @Test
    fun `primitive array members compile as scalar native predicates`() {
        val schema = bind(
            logical("scores" to array(scalar(QueryValueType.INTEGER))),
            TypeMapping.of {
                it.properties("scores") { it.long_ { it } }
            }
        )
        val compiler = object : me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterCompiler() {}
        val filter = me.ahoo.wow.api.query.EqualFilter(
            QueryField("scores"),
            tools.jackson.databind.node.IntNode.valueOf(3)
        )
        compiler.compile(filter, schema).term().field().assert().isEqualTo("scores")
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(
                me.ahoo.wow.api.query.EqualFilter(
                    QueryField("scores"),
                    tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(3)
                ),
                schema
            )
        }
    }

    @Test
    fun `ignore above retracts native operations while preserving source and bounded enum proof`() {
        val bounded = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            enumValues = listOf(
                tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("yes"),
                tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("no")
            )
        )
        val schema =
            bind(
                logical(
                    "state" to objectValue(mapOf("unbounded" to scalar(QueryValueType.STRING), "bounded" to bounded))
                ),
                TypeMapping.of {
                    it.properties("state.unbounded") { it.keyword { it.ignoreAbove(3) } }
                        .properties("state.bounded") { it.keyword { it.ignoreAbove(3) } }
                }
            )
        schema.field(QueryField("state.unbounded"))!!.bindings.assert().isEmpty()
        schema.field(QueryField("state.unbounded"))!!.projectionField.assert().isEqualTo(QueryField("state.unbounded"))
        schema.path("state.bounded", QueryCapability.EXACT_MATCH).assert().isEqualTo("state.bounded")
        schema.path("state.bounded", QueryCapability.PRESENCE).assert().isEqualTo("state.bounded")
        schema.path("state.bounded", QueryCapability.CURSOR_SORT).assert().isEqualTo("state.bounded")
    }

    @Test
    fun `lossy known map values reject positive negative and access predicates before client io`() {
        val client = io.mockk.mockk<org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient>()
        val backend = me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryBackend(
            me.ahoo.wow.modeling.MaterializedNamedAggregate("test", "lossy"),
            client
        )
        val definition = logical(
            "tags" to objectValue(additional = array(scalar(QueryValueType.STRING))),
            "deleted" to scalar(QueryValueType.BOOLEAN)
        )
        val schema = bind(
            definition,
            TypeMapping.of {
                it.properties("tags.department") { it.keyword { it.ignoreAbove(3) } }
                    .properties("deleted") { it.boolean_ { it } }
            }
        )
        val field = QueryField("tags.department")
        val long = tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("x".repeat(9000))
        val access = with(me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy) {
            mapOf("department" to listOf("eng")).toFilterExpression()
        }
        listOf(
            me.ahoo.wow.api.query.EqualFilter(field, long),
            me.ahoo.wow.api.query.NotEqualFilter(field, long),
            access
        ).forEach { filter ->
            assertThrows<QuerySchemaValidationException> {
                backend.list(ListQuery(filter = filter, limit = 0), schema)
            }
        }
        io.mockk.verify(exactly = 0) {
            client.search(
                any<co.elastic.clients.elasticsearch.core.SearchRequest>(),
                tools.jackson.databind.node.ObjectNode::class.java
            )
        }
        io.mockk.verify(exactly = 0) {
            client.openPointInTime(any<co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest>())
        }
    }

    @Test
    fun `nullable nested items preserve element scope but non object carriers do not`() {
        val item = objectValue(mapOf("code" to scalar(QueryValueType.STRING)))
        val mapping = TypeMapping.of {
            it.properties("items") { it.nested { it.properties("code") { it.keyword { it } } } }
        }
        val nullable = bind(logical("items" to array(union(item, QueryValueSchema(QueryValueKind.NULL)))), mapping)
        nullable.path("items", QueryCapability.ELEMENT_SCOPE).assert().isEqualTo("items")
        nullable.path("items.code", QueryCapability.EXACT_MATCH).assert().isEqualTo("items.code")
        listOf(scalar(QueryValueType.STRING), QueryValueSchema(QueryValueKind.UNKNOWN)).forEach { invalid ->
            val schema = bind(logical("items" to array(union(item, invalid))), mapping)
            schema.path("items", QueryCapability.ELEMENT_SCOPE).assert().isNull()
            schema.path("items.code", QueryCapability.EXACT_MATCH).assert().isNull()
        }
    }

    @Test
    fun `disabled ancestors remove index capabilities while retaining source for aliases and multifields`() {
        val definition = logical(
            "obj" to objectValue(mapOf("code" to scalar(QueryValueType.STRING))),
            "alias" to scalar(QueryValueType.STRING),
        )
        listOf(false, true).forEach { disableRoot ->
            val schema = bind(
                definition,
                TypeMapping.of { root ->
                    root.enabled(!disableRoot)
                        .properties("obj") {
                            it.`object` { obj ->
                                obj.enabled(disableRoot).properties("code") {
                                    it.keyword { keyword ->
                                        keyword.fields("exact") { it.keyword { it } }
                                    }
                                }
                            }
                        }
                        .properties("alias") { it.alias { it.path("obj.code") } }
                }
            )
            listOf("obj.code", "alias").forEach { field ->
                schema.field(QueryField(field))!!.bindings.assert().isEmpty()
                schema.field(QueryField(field))!!.projectionField.assert().isEqualTo(QueryField("obj.code"))
            }
        }
    }

    @Test
    fun `normalized keyword rejects source literal operations and selects a lossless sibling`() {
        val definition = logical("code" to scalar(QueryValueType.STRING), "name" to scalar(QueryValueType.STRING))
        val schema = bind(
            definition,
            TypeMapping.of {
                it.properties("code") { it.keyword { it.normalizer("lowercase") } }
                    .properties("name") {
                        it.keyword { keyword ->
                            keyword.normalizer("lowercase").fields("exact") { it.keyword { it } }
                                .fields("text") { it.text { it.analyzer("standard") } }
                        }
                    }
            }
        )
        listOf(
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.RANGE,
            QueryCapability.SORT,
            QueryCapability.CURSOR_SORT,
            QueryCapability.AGGREGATE_TERMS
        ).forEach { capability ->
            schema.path("code", capability).assert().isNull()
            schema.path("name", capability).assert().isEqualTo("name.exact")
        }
        schema.path("code", QueryCapability.PRESENCE).assert().isEqualTo("code")
        schema.path("name", QueryCapability.FULL_TEXT_TERMS).assert().isEqualTo("name.text")
        schema.path("name", QueryCapability.FULL_TEXT_PHRASE).assert().isEqualTo("name.text")
        schema.field(QueryField("code"))!!.projectionField.assert().isEqualTo(QueryField("code"))
    }

    @Test
    fun `disabled source rejects reads but retains count and aggregation capabilities`() {
        val schema = bind(
            logical("code" to scalar(QueryValueType.STRING)),
            TypeMapping.of {
                it.source { it.enabled(false) }.properties("code") { it.keyword { it } }
            }
        )
        assertThrows<QuerySchemaValidationException> {
            validateQuery(ListQuery(MatchAllFilter), schema)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                ListQuery(
                    MatchAllFilter,
                    projection = Projection(include = listOf(QueryField("code"))),
                ),
                schema
            )
        }
        validateQuery(MatchAllFilter, schema)
        schema.path("code", QueryCapability.AGGREGATE_TERMS).assert().isEqualTo("code")
    }

    @Test
    fun `source pruning rejects incomplete parent reads while preserving available alias projections`() {
        val definition = logical(
            "obj" to objectValue(
                mapOf(
                    "code" to scalar(QueryValueType.STRING), "secret" to scalar(QueryValueType.STRING)
                )
            ),
            "alias" to scalar(QueryValueType.STRING)
        )
        listOf(
            TypeMapping.of { it.source { it.excludes("obj.secret") } },
            TypeMapping.of { it.source { it.includes("obj.code") } },
        ).forEach { source ->
            val mapping = TypeMapping.of {
                it.source(source.source()!!).properties("obj") {
                    it.`object` { obj ->
                        obj.properties("code") { it.keyword { it.fields("exact") { it.keyword { it } } } }
                            .properties("secret") { it.keyword { it } }
                    }
                }.properties("alias") { it.alias { it.path("obj.code") } }
            }
            val schema = bind(definition, mapping)
            listOf("obj", "obj.secret").forEach { field ->
                assertThrows<QuerySchemaValidationException> {
                    validateQuery(
                        ListQuery(
                            MatchAllFilter,
                            projection = Projection(include = listOf(QueryField(field))),
                        ),
                        schema
                    )
                }
            }
            assertThrows<QuerySchemaValidationException> {
                validateQuery(ListQuery(MatchAllFilter), schema)
            }
            schema.field(QueryField("alias"))!!.projectionField.assert().isEqualTo(QueryField("obj.code"))
            validateQuery(
                ListQuery(
                    MatchAllFilter,
                    projection = Projection(include = listOf(QueryField("alias"))),
                ),
                schema
            )
        }
    }

    @Test
    fun `source wildcard pruning retracts incomplete containers and keeps provable leaves`() {
        val definition = logical(
            "obj" to objectValue(
                mapOf(
                    "code" to scalar(QueryValueType.STRING),
                    "secret" to scalar(QueryValueType.STRING)
                )
            ),
            "safe" to scalar(QueryValueType.STRING)
        )
        val schema = bind(
            definition,
            TypeMapping.of {
                it.source { it.includes("obj.*", "safe").excludes("obj.secret*") }
            }
        )
        schema.field(QueryField("obj"))!!.projectionField.assert().isNull()
        schema.field(QueryField("obj.secret"))!!.projectionField.assert().isNull()
        schema.field(QueryField("obj.code"))!!.projectionField.assert().isEqualTo(QueryField("obj.code"))
        schema.field(QueryField("safe"))!!.projectionField.assert().isEqualTo(QueryField("safe"))
        val uncertain = bind(definition, TypeMapping.of { it.source { it.excludes("*.secret") } })
        uncertain.field(QueryField("obj"))!!.projectionField.assert().isNull()
    }

    private fun QueryModelSchema.path(field: String, capability: QueryCapability): String? =
        field(QueryField(field))?.binding(capability)?.physicalField?.path

    private fun bind(definition: LogicalQuerySchema, mapping: TypeMapping) =
        ElasticsearchQuerySchemaAdapter.bind(definition, ElasticsearchIndexMapping.from("test", mapping))

    private fun logical(vararg fields: Pair<String, QueryValueSchema>) = LogicalQuerySchema(objectValue(fields.toMap()))
    private fun scalar(type: QueryValueType) = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(type))
    private fun array(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)
    private fun union(vararg values: QueryValueSchema) = QueryValueSchema(
        QueryValueKind.UNION,
        alternatives = values.toList()
    )
    private fun objectValue(
        properties: Map<String, QueryValueSchema> = emptyMap(),
        additional: QueryValueSchema? = null
    ) =
        QueryValueSchema(QueryValueKind.OBJECT, properties = properties, additionalProperties = additional)
}
