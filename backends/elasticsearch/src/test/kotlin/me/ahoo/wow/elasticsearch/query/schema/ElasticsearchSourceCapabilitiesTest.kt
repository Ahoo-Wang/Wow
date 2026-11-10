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
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.bind
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.logical
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.objectValue
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.path
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterFixtures.scalar
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** What `_source` grants: disabled ancestors, a disabled source, and source pruning with and without wildcards. */
class ElasticsearchSourceCapabilitiesTest {
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
    fun `disabled source rejects reads but retains count and aggregation capabilities`() {
        val schema = bind(
            logical("code" to scalar(QueryValueType.STRING)),
            TypeMapping.of {
                it.source { it.enabled(false) }.properties("code") { it.keyword { it } }
            }
        )
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)
        }
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.list(
                ListQuery(
                    MatchAllFilter,
                    projection = Projection(include = listOf(QueryField("code"))),
                ),
                schema
            )
        }
        QueryAdmission.Trusted.count(me.ahoo.wow.api.query.CountQuery(MatchAllFilter), schema)
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
                    QueryAdmission.Trusted.list(
                        ListQuery(
                            MatchAllFilter,
                            projection = Projection(include = listOf(QueryField(field))),
                        ),
                        schema
                    )
                }
            }
            assertThrows<QuerySchemaValidationException> {
                QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)
            }
            schema.field(QueryField("alias"))!!.projectionField.assert().isEqualTo(QueryField("obj.code"))
            QueryAdmission.Trusted.list(
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
}
