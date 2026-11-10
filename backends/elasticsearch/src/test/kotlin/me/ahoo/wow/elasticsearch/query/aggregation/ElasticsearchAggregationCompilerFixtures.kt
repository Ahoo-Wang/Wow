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

package me.ahoo.wow.elasticsearch.query.aggregation

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.dsl.aggregation
import me.ahoo.wow.api.query.fields
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.compile
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.elasticsearch.query.schema.bind
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import java.util.concurrent.TimeUnit

/** The logical schema and its bound index mapping the tests of the aggregation compiler compile against, one per test. */
internal abstract class ElasticsearchAggregationCompilerFixtures {
    protected val compiler = ElasticsearchAggregationCompiler
    protected val scalar = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER))
    protected val text = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))
    private val temporal = QueryValueSchema(
        QueryValueKind.SCALAR,
        valueTypes = setOf(QueryValueType.INTEGER),
        semanticType = Temporal.Epoch(TimeUnit.MICROSECONDS)
    )

    /** A string marked as prose a reader searches by words (`@QueryText`). */
    private val prose = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), text = true)
    private val definition = LogicalQuerySchema(
        obj(
            mapOf(
                "deleted" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN)),
                "aggregateId" to text,
                "tenantId" to text,
                "ownerId" to text,
                "spaceId" to text,
                "amount" to scalar,
                "createdAt" to temporal,
                "name" to prose,
                "customerId" to text,
                "tags" to array(text),
                "orders" to array(
                    obj(
                        mapOf(
                            "status" to text,
                            "createdAt" to temporal,
                            "lines" to array(obj(mapOf("quantity" to scalar, "amount" to scalar, "productId" to text, "createdAt" to temporal))),
                        )
                    )
                ),
            )
        )
    )
    protected val schema = ElasticsearchQuerySchemaAdapter.bind(
        definition,
        ElasticsearchIndexMapping.from(
            "test",
            TypeMapping.of {
                it.properties("deleted") { it.boolean_ { it } }
                    .properties("aggregateId") { it.keyword { it } }
                    .properties("tenantId") { it.keyword { it } }
                    .properties("ownerId") { it.keyword { it } }
                    .properties("spaceId") { it.keyword { it } }
                    .properties("amount") { it.long_ { it } }
                    .properties("createdAt") { it.long_ { it } }
                    .properties("name") { it.text { it.fields("keyword") { it.keyword { it } } } }
                    .properties("customerId") { it.text { it.fields("keyword") { it.keyword { it } } } }
                    .properties("tags") { it.keyword { it } }
                    .properties("orders") {
                        it.nested { orders ->
                            orders.properties("status") { it.keyword { it } }
                                .properties("createdAt") { it.long_ { it } }
                                .properties("lines") {
                                    it.nested { lines ->
                                        lines.properties("quantity") { it.long_ { it } }
                                            .properties("createdAt") { it.long_ { it } }
                                            .properties("amount") { it.long_ { it } }
                                            .properties(
                                                "productId"
                                            ) { it.text { it.fields("keyword") { it.keyword { it } } } }
                                    }
                                }
                        }
                    }
            }
        )
    )

    protected fun obj(properties: Map<String, QueryValueSchema>) = QueryValueSchema(
        QueryValueKind.OBJECT,
        properties = properties
    )
    protected fun array(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)

    /** Metric filter shape rules are enforced by query validation before any backend compiles the query. */
    protected fun compileValidated(query: AggregationQuery, schema: QueryModelSchema) =
        compiler.compile(query.also { QueryAdmission.Trusted.aggregate(it, schema) }, schema)
}
