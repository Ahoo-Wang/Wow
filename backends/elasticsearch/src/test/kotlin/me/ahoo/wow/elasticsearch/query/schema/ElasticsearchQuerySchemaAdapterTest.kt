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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.compile
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.array
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.bind
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.logical
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.objectValue
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.path
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.scalar
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.union
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.spec.MetricSpec
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
    fun `the index's max_result_window bounds offset pages`() {
        val logical = logical("name" to scalar(QueryValueType.STRING))
        val mapping = TypeMapping.of { it.properties("name") { it.keyword { it } } }

        ElasticsearchQuerySchemaAdapter.facts(logical, ElasticsearchIndexMapping.from("test", mapping))
            .storage.paging.maxOffsetWindow.assert().isEqualTo(10_000)
        ElasticsearchQuerySchemaAdapter.facts(
            logical,
            ElasticsearchIndexMapping.from("test", mapping, maxResultWindow = 20),
        ).storage.paging.maxOffsetWindow.assert().isEqualTo(20)
    }

    @Test
    fun `one primary shard takes a top N by a count natively, several do not`() {
        val logical = logical("name" to scalar(QueryValueType.STRING))
        val mapping = TypeMapping.of { it.properties("name") { it.keyword { it } } }
        val single = ElasticsearchIndexMapping.from("test", mapping, singleShard = true)

        ElasticsearchQuerySchemaAdapter.facts(logical, single).storage.aggregation.termsTopN
            .assert().containsExactlyInAnyOrder(MetricSpec.COUNT, MetricSpec.DISTINCT_COUNT)
        ElasticsearchQuerySchemaAdapter.facts(logical, ElasticsearchIndexMapping.from("test", mapping))
            .storage.aggregation.termsTopN.assert().isEmpty()
        // A name over several physical indices merges their top buckets as shards do.
        ElasticsearchIndexMapping.common("alias", mapOf("a" to single, "b" to single)).singleShard.assert().isFalse()
        ElasticsearchIndexMapping.common("alias", mapOf("a" to single)).singleShard.assert().isTrue()
    }

    @Test
    fun `nested descendants keep their element scope`() {
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
        val price = schema.field(QueryField("orders.price"))!!
        price.elementAncestors.assert().isEqualTo(listOf(QueryField("orders")))
        price.binding(QueryCapability.RANGE)!!.physicalField.assert().isEqualTo(QueryField("orders.price"))
        price.bindings.assert().doesNotContainKey(QueryCapability.CURSOR_SORT)
        // A nested child is addressed through its nested mapping; the nested field itself lies in none.
        price.binding(QueryCapability.SORT)!!.physicalScope.assert().isEqualTo(QueryField("orders"))
        schema.field(QueryField("orders"))!!.binding(QueryCapability.ELEMENT_SCOPE)!!.physicalScope.assert().isNull()
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
    fun `nested text fields are searched with match inside the nested query`() {
        val schema =
            bind(
                logical(
                    "items" to array(
                        objectValue(mapOf("name" to scalar(QueryValueType.STRING), "code" to scalar(QueryValueType.STRING)))
                    )
                ),
                TypeMapping.of {
                    it.properties("items") {
                        it.nested { nested ->
                            nested.properties("name") { it.text { it } }.properties("code") { it.keyword { it } }
                        }
                    }
                }
            )
        val compiler = me.ahoo.wow.elasticsearch.query.ElasticsearchFilterCompiler
        val search = me.ahoo.wow.api.query.ElementMatchFilter(
            QueryField("items"),
            me.ahoo.wow.api.query.SearchFilter("widget", setOf(QueryField("name"))),
        )

        val nested = compiler.compile(search, schema).nested()
        nested.path().assert().isEqualTo("items")
        nested.query().multiMatch().fields().assert().containsExactly("items.name")
        nested.query().multiMatch().query().assert().isEqualTo("widget")
        // A keyword is not full text: storage grants no search, so admission rejects instead of emulating it.
        schema.path("items.code", QueryCapability.FULL_TEXT_TERMS).assert().isNull()
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(
                me.ahoo.wow.api.query.ElementMatchFilter(
                    QueryField("items"),
                    me.ahoo.wow.api.query.SearchFilter("widget", setOf(QueryField("code"))),
                ),
                schema,
            )
        }
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
        val rule = me.ahoo.wow.query.schema.MaskRule(SensitivityLevel.DISPLAY)
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

    @Test
    fun `primitive array members compile as scalar native predicates`() {
        val schema = bind(
            logical("scores" to array(scalar(QueryValueType.INTEGER))),
            TypeMapping.of {
                it.properties("scores") { it.long_ { it } }
            }
        )
        val compiler = me.ahoo.wow.elasticsearch.query.ElasticsearchFilterCompiler
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
    fun `a provisional mapping binds the paths it does not map yet and keeps the refusals of those it maps`() {
        val definition = logical(
            "tenantId" to scalar(QueryValueType.STRING),
            "tags" to objectValue(additional = array(scalar(QueryValueType.STRING))),
            "state" to objectValue(
                mapOf(
                    "status" to scalar(QueryValueType.STRING),
                    "capped" to scalar(QueryValueType.STRING),
                    "flag" to scalar(QueryValueType.BOOLEAN),
                )
            ),
        )
        val mapping = TypeMapping.of {
            it.properties("tenantId") { it.keyword { it } }
                .properties("state.capped") { it.keyword { it.ignoreAbove(3) } }
        }
        val facts = ElasticsearchQuerySchemaAdapter.facts(
            definition,
            ElasticsearchIndexMapping.from("test", mapping, provisional = true),
        )
        facts.provisional.assert().isTrue()
        val schema = facts.compile(QueryModel.SNAPSHOT, definition)
        schema.provisional.assert().isTrue()
        schema.capabilities.assert().contains(QueryCapability.FULL_TEXT_TERMS)
        schema.path("tenantId", QueryCapability.EXACT_MATCH).assert().isEqualTo("tenantId")
        schema.path("tenantId", QueryCapability.FULL_TEXT_TERMS).assert().isNull()
        listOf(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
            QueryCapability.SORT,
            QueryCapability.CURSOR_SORT,
            QueryCapability.AGGREGATE_TERMS,
        ).forEach { capability ->
            schema.path("state.status", capability).assert().isEqualTo("state.status")
        }
        schema.path("state.flag", QueryCapability.EXACT_MATCH).assert().isEqualTo("state.flag")
        schema.path("state.flag", QueryCapability.RANGE).assert().isNull()
        schema.path("state.flag", QueryCapability.LITERAL_MATCH).assert().isNull()
        schema.field(QueryField("state.capped"))!!.bindings.assert().isEmpty()
        schema.path("tags.department", QueryCapability.EXACT_MATCH).assert().isEqualTo("tags.department")

        val existing = bind(definition, mapping)
        existing.provisional.assert().isFalse()
        existing.field(QueryField("state.status"))!!.bindings.assert().isEmpty()
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
}
