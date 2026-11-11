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

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.bind
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.logical
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.objectValue
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.path
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.prose
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.scalar
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import org.junit.jupiter.api.Test

/** What a keyword's indexing grants: integral fields, unindexed and ambiguous keywords, `ignore_above`, enums, normalizers. */
class ElasticsearchKeywordCapabilitiesTest {
    @Test
    fun `a decimal on an integral field is neither filtered, sorted nor aggregated`() {
        // Dynamic mapping makes a decimal `long` when the first value it sees is integral; Elasticsearch then truncates
        // 12.75 to 12 in the index, so a sum, a range or a sort of it would be silently wrong.
        val logical = logical("price" to scalar(QueryValueType.DECIMAL), "count" to scalar(QueryValueType.INTEGER))
        fun keys(mapping: TypeMapping, path: String) = bind(logical, mapping).field(QueryField(path))!!.bindings.keys
        val truncating = TypeMapping.of {
            it.properties("price") { p -> p.long_ { l -> l } }.properties("count") { p -> p.long_ { l -> l } }
        }

        keys(truncating, "price").assert().containsOnly(QueryCapability.PRESENCE)
        keys(truncating, "count").assert().contains(QueryCapability.AGGREGATE_NUMERIC, QueryCapability.RANGE)
        val floating = listOf(
            TypeMapping.of { it.properties("price") { p -> p.double_ { d -> d } } },
            TypeMapping.of { it.properties("price") { p -> p.float_ { f -> f } } },
            TypeMapping.of { it.properties("price") { p -> p.scaledFloat { f -> f.scalingFactor(100.0) } } },
        )
        floating.forEach { mapping ->
            keys(mapping, "price").assert()
                .contains(
                    QueryCapability.EXACT_MATCH,
                    QueryCapability.RANGE,
                    QueryCapability.SORT,
                    QueryCapability.AGGREGATE_NUMERIC,
                )
        }
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
            logical("name" to prose()),
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
    fun `ignore above below the default templates' cap retracts native operations while preserving source and bounded enum proof`() {
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
    fun `a keyword capped at the default templates' ignore above has the operators of an uncapped keyword`() {
        val definition = logical(
            "state" to objectValue(
                mapOf(
                    "definitionId" to scalar(QueryValueType.STRING),
                    "capped" to scalar(QueryValueType.STRING),
                    "uncapped" to scalar(QueryValueType.STRING),
                )
            )
        )
        val schema = bind(
            definition,
            TypeMapping.of {
                it.properties("state.definitionId") { it.keyword { it.ignoreAbove(UNCAPPED_IGNORE_ABOVE) } }
                    .properties("state.capped") { it.keyword { it.ignoreAbove(UNCAPPED_IGNORE_ABOVE - 1) } }
                    .properties("state.uncapped") { it.keyword { it } }
            }
        )
        val uncapped = schema.field(QueryField("state.uncapped"))!!.bindings.keys
        uncapped.assert().contains(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.SORT,
            QueryCapability.CURSOR_SORT,
            QueryCapability.AGGREGATE_TERMS,
        )
        schema.field(QueryField("state.definitionId"))!!.bindings.keys.assert().isEqualTo(uncapped)
        uncapped.forEach { capability ->
            schema.path("state.definitionId", capability).assert().isEqualTo("state.definitionId")
        }
        schema.field(QueryField("state.capped"))!!.bindings.assert().isEmpty()
        schema.field(QueryField("state.capped"))!!.projectionField.assert().isEqualTo(QueryField("state.capped"))
    }

    @Test
    fun `an enum keeps its bounded proof below the cap and is admitted at it`() {
        val enum = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            enumValues = listOf(tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("longer")),
        )
        val schema = bind(
            logical("state" to objectValue(mapOf("short" to enum, "long" to enum))),
            TypeMapping.of {
                it.properties("state.short") { it.keyword { it.ignoreAbove(3) } }
                    .properties("state.long") { it.keyword { it.ignoreAbove(UNCAPPED_IGNORE_ABOVE) } }
            }
        )
        schema.field(QueryField("state.short"))!!.bindings.assert().isEmpty()
        schema.path("state.long", QueryCapability.EXACT_MATCH).assert().isEqualTo("state.long")
    }

    @Test
    fun `normalized keyword rejects source literal operations and selects a lossless sibling`() {
        val definition = logical("code" to scalar(QueryValueType.STRING), "name" to prose())
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
}
