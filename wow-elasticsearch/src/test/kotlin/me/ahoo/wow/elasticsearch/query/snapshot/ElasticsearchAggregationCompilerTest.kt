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

package me.ahoo.wow.elasticsearch.query.snapshot

import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationCompiler
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationMetric
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class ElasticsearchAggregationCompilerTest {
    private val compiler = ElasticsearchAggregationCompiler(SnapshotFilterCompiler)
    private val scalar = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.INTEGER))
    private val text = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))
    private val temporal = QueryValueSchema(
        QueryValueKind.SCALAR,
        valueTypes = setOf(QueryValueType.INTEGER),
        semanticType = Temporal.Epoch(TimeUnit.MICROSECONDS)
    )
    private val definition = LogicalQuerySchema(
        obj(
            mapOf(
                "deleted" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN)),
                "amount" to scalar,
                "createdAt" to temporal,
                "name" to text,
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
    private val schema = ElasticsearchQuerySchemaAdapter.bind(
        definition,
        ElasticsearchIndexMapping.from(
            "test",
            TypeMapping.of {
                it.properties("deleted") { it.boolean_ { it } }
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

    @Test
    fun `scalar numeric field keeps native metric while array branches use runtime cardinality`() {
        val variants = listOf(
            scalar to false,
            QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(scalar, QueryValueSchema(QueryValueKind.NULL))) to false,
            array(scalar) to true,
            QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(array(scalar), QueryValueSchema(QueryValueKind.NULL))) to true,
            QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(scalar, array(scalar))) to true,
        )
        variants.forEach { (value, runtimeRequired) ->
            val bound = ElasticsearchQuerySchemaAdapter.bind(
                LogicalQuerySchema(
                    obj(
                        mapOf(
                            "amount" to value,
                            "deleted" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN))
                        )
                    )
                ),
                ElasticsearchIndexMapping.from(
                    "test",
                    TypeMapping.of {
                        it.properties(
                            "amount"
                        ) { it.long_ { it } }.properties("deleted") { it.boolean_ { it } }
                    }
                ),
            )
            val plan = compiler.compile(aggregation { sum("amount", "total") }, bound)
            plan.runtimeMappings.isNotEmpty().assert().isEqualTo(runtimeRequired)
            (plan.metrics.single() as ElasticsearchAggregationMetric.Numeric).field.assert()
                .isEqualTo(if (runtimeRequired) "__wow_expression_0" else "amount")
        }
    }

    @Test
    fun `numeric epoch histogram uses request local parameterized date runtime`() {
        val plan = compiler.compile(
            aggregation {
                dateHistogram("createdAt", AggregationDateUnit.DAY, "day")
                count("count")
            },
            schema
        )
        plan.groupSources.single().value().dateHistogram().field().assert().isEqualTo("__wow_date_histogram_0")
        val runtime = plan.runtimeMappings.getValue("__wow_date_histogram_0")
        runtime.type().assert().isEqualTo(RuntimeFieldType.Date)
        val script = requireNotNull(runtime.script())
        script.params().values.map { it.to(Any::class.java) }.assert().contains("createdAt", 1L, 1_000L)
        requireNotNull(script.source()).scriptString().assert().contains("doc.containsKey").contains("size() == 1")
            .contains("Double.isFinite").contains("Long.MAX_VALUE")
        compiler.compile(aggregation { count("count") }, schema).runtimeMappings.assert().isEmpty()
    }

    @Test
    fun `terms and any consume logical names and choose keyword native binding`() {
        val plan = compiler.compile(
            aggregation {
                terms("name", "name")
                any("name", "sample")
            },
            schema
        )
        plan.groupSources.single().value().terms().field().assert().isEqualTo("name.keyword")
        (plan.metrics.single() as ElasticsearchAggregationMetric.Any).field.assert().isEqualTo("name.keyword")
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(aggregation { any("name.keyword", "sample") }, schema)
        }
    }

    @Test
    fun `plan should map distinct count and percentile metrics`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                distinctCount("customerId", "customers")
                percentile("amount", 95.0, "p95")
                stddev("amount", "stddev")
                variance("amount", "variance")
            },
            schema,
        )

        plan.metrics.assert().containsExactly(
            ElasticsearchAggregationMetric.DistinctCount("customers", "customerId.keyword"),
            ElasticsearchAggregationMetric.Percentile("p95", "amount", 95.0),
            ElasticsearchAggregationMetric.Numeric("stddev", AggregationFunction.STDDEV, "amount"),
            ElasticsearchAggregationMetric.Numeric("variance", AggregationFunction.VARIANCE, "amount"),
        )
    }

    @Test
    fun `non-field distinct count and percentile expressions compile to runtime fields`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                distinctCount(field("amount") + constant(0.0), "amounts")
                percentile(field("amount") * constant(1.0), 95.0, "p95")
            },
            schema,
        )

        plan.runtimeMappings.keys.assert().containsExactly("__wow_expression_0", "__wow_expression_1")
        plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.DistinctCount>().single().field.assert()
            .isEqualTo("__wow_expression_0")
        val percentile = plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Percentile>().single()
        percentile.field.assert().isEqualTo("__wow_expression_1")
        percentile.percentile.assert().isEqualTo(95.0)
        plan.runtimeMappings.values.forEach { runtimeField ->
            runtimeField.type().assert().isEqualTo(RuntimeFieldType.Double)
        }
    }

    @Test
    fun `missing native field never falls back to physical input`() {
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(
                aggregation {
                    terms("unknown", "value")
                    count("count")
                },
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(
                aggregation {
                    dateHistogram("amount", AggregationDateUnit.DAY, "day")
                    count("count")
                },
                schema
            )
        }
    }

    @Test
    fun `array valued metric filter fields are rejected`() {
        val exception = assertThrows<QuerySchemaValidationException> {
            compiler.compile(aggregation { count("tagged") { "tags" eq "premium" } }, schema)
        }
        exception.message.assert().contains("must be scalar")
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(
                aggregation {
                    expand("orders")
                    count("recent") { "lines".elementMatch { "quantity" gt 0 } }
                },
                schema,
            )
        }
    }

    @Test
    fun `plan should compile metric filters into scoped queries`() {
        val plan = compiler.compile(
            aggregation {
                count("paid") { "deleted" eq false }
                sum("amount", "paidAmount") { "deleted" eq false }
            },
            schema,
        )

        val count = plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Count>().single()
        count.filter.assert().isNotNull()
        count.filter!!.term().field().assert().isEqualTo("deleted")
        val numeric = plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Numeric>().single()
        numeric.filter.assert().isNotNull()
        numeric.filter!!.term().field().assert().isEqualTo("deleted")

        val scoped = compiler.compile(
            aggregation {
                expand("orders")
                count("paidOrders") { "status" eq "PAID" }
            },
            schema,
        )
        (scoped.metrics.single() as ElasticsearchAggregationMetric.Count).filter!!.term().field()
            .assert().isEqualTo("orders.status")

        compiler.compile(aggregation { count("count") }, schema).metrics.single().filter.assert().isNull()
    }

    @Test
    fun `two nested scopes keep predicates relative and native paths absolute`() {
        val query = aggregation {
            expand("orders") { "status" eq "PAID" }
            expand("lines") { "quantity" gt 0 }
            terms("productId", "product")
            histogram("amount", 10.0, "range")
            sum("amount", "total")
        }
        val plan = compiler.compile(query, schema)
        plan.elements.map { it.path }.assert().containsExactly("orders", "orders.lines")
        plan.elements[0].filter.term().field().assert().isEqualTo("orders.status")
        plan.elements[1].filter.range().untyped().field().assert().isEqualTo(
            "orders.lines.quantity"
        )
        plan.groupSources[0].value().terms().field().assert().isEqualTo("orders.lines.productId.keyword")
        (plan.metrics.single() as ElasticsearchAggregationMetric.Numeric).field.assert().isEqualTo(
            "orders.lines.amount"
        )
        query.elements[1].path.assert().isEqualTo(QueryField("lines"))
    }

    @Test
    fun `root and every element relative time share one compilation instant`() {
        val now = java.time.Instant.parse("1970-01-02T12:00:00Z")
        val utc = java.time.ZoneId.of("UTC")
        val query = aggregation {
            filter { "createdAt".today(utc) }
            expand("orders") { "createdAt".today(utc) }
            expand("lines") { "createdAt".today(utc) }
            count("count")
        }
        val plan = compiler.compile(query, schema, now)
        (listOf(plan.rootQuery) + plan.elements.map { it.filter }).forEach { filter ->
            filter.toString().assert().contains("86400000000").contains("172800000000")
        }
        plan.elements.forEach { it.filter.toString().assert().doesNotContain("deleted") }
        val next = compiler.compile(query, schema, now.plusSeconds(86400))
        (listOf(next.rootQuery) + next.elements.map { it.filter }).forEach { filter ->
            filter.toString().assert().contains("172800000000").contains("259200000000")
        }
    }

    @Test
    fun `absolute child input is rejected in element scope`() {
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(
                aggregation {
                    expand("orders")
                    terms("orders.status", "status")
                    count("count")
                },
                schema
            )
        }
    }

    @Test
    fun `computed metric emits guarded parameterized native fields`() {
        val plan = compiler.compile(aggregation { sum(field("amount") / constant(2.0), "total") }, schema)
        val runtime = plan.runtimeMappings.values.single()
        runtime.type().assert().isEqualTo(RuntimeFieldType.Double)
        val script = runtime.script()!!
        script.params().values.map { it.to(Any::class.java) }.assert().contains("amount", 2.0)
        script.source()!!.scriptString().assert().contains("Double.isFinite").contains("!= 0.0")
            .contains("size() == 1").doesNotContain("doc['amount']")
    }

    @Test
    fun `maximum expression fits default script limit`() {
        val plan = compiler.compile(aggregation { sum(maximumExpression(), "total") }, schema)
        val source = plan.runtimeMappings.values.single().script()!!.source()!!.scriptString()
        source.toByteArray(Charsets.UTF_8).size.assert().isLessThanOrEqualTo(65_535)
    }

    private fun maximumExpression(depth: Int = 8): AggregationExpression = if (depth == 1) {
        AggregationExpression.Field(QueryField("amount"))
    } else {
        AggregationExpression.Binary(
            AggregationExpressionOperator.ADD,
            maximumExpression(depth - 1),
            maximumExpression(depth - 1)
        )
    }

    private fun obj(properties: Map<String, QueryValueSchema>) = QueryValueSchema(
        QueryValueKind.OBJECT,
        properties = properties
    )
    private fun array(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)
}
