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

import co.elastic.clients.elasticsearch._types.Script
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.dsl.aggregation
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.compile
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.elasticsearch.query.schema.bind
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId

internal class ElasticsearchAggregationCompilerTest : ElasticsearchAggregationCompilerFixtures() {
    @Test
    fun `FIRST and LAST read the top hit of documents that have both fields`() {
        val plan = compiler.compile(
            aggregation {
                terms("name", "name")
                first("name", "open", orderBy = "amount")
                last("amount", "close", orderBy = "amount") { "customerId" eq "vip" }
            },
            schema,
        )
        val (open, close) = plan.metrics.map { it as ElasticsearchAggregationMetric.Edge }
        open.field.assert().isEqualTo("name.keyword")
        open.orderBy.assert().isEqualTo("amount")
        open.last.assert().isFalse()
        close.last.assert().isTrue()
        open.filter.bool().filter().map { it.exists().field() }.assert().containsExactly("name.keyword", "amount")
        close.filter.bool().filter().assert().hasSize(3)
    }

    @Test
    fun `the compiler refuses the operators Elasticsearch declares residual`() {
        listOf(
            aggregation {
                dateHistogram("createdAt", AggregationDateUnit.DAY, "day", dense = true)
                count("count")
            },
            aggregation {
                terms("name", "name")
                count("c")
                having { "c" gte 2.0 }
            },
            // A count sort over one TERMS group is a native top-N (ElasticsearchTermsTopNTest); a sum is not.
            aggregation {
                terms("name", "name")
                sum("amount", "c")
                sort { "c".desc() }
            },
        ).forEach { query ->
            assertThrows<QueryExecutionException> { compiler.compile(query, schema) }
        }
        compiler.compile(
            aggregation {
                dateHistogram("createdAt", AggregationDateUnit.DAY, "day")
                count("count")
            },
            schema,
        ).groupSources.single().name().assert().isEqualTo("day")
    }

    @Test
    fun `windows are computed by the core and never compiled`() {
        val query = aggregation {
            terms("name", "name")
            count("c")
            derived("total") { total("c") }
        }
        assertThrows<IllegalStateException> { compiler.compile(query, schema) }
    }

    @Test
    fun `plan should map distinct count and percentile metrics`() {
        val plan = ElasticsearchAggregationCompiler.compile(
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
            compileValidated(aggregation { count("tagged") { "tags" eq "premium" } }, schema)
        }
        exception.message.assert().contains("must be scalar")
        assertThrows<QuerySchemaValidationException> {
            compileValidated(
                aggregation {
                    expand("orders")
                    count("recent") { "lines".elementMatch { "quantity" gt 0 } }
                },
                schema,
            )
        }
    }

    @Test
    fun `element scoped and union array metric filter fields are rejected`() {
        val tags = array(text)
        val bound = ElasticsearchQuerySchemaAdapter.bind(
            LogicalQuerySchema(
                obj(
                    mapOf(
                        "deleted" to QueryValueSchema(
                            QueryValueKind.SCALAR,
                            valueTypes = setOf(QueryValueType.BOOLEAN),
                        ),
                        "notes" to QueryValueSchema(QueryValueKind.UNION, alternatives = listOf(text, tags)),
                        "orders" to array(obj(mapOf("lines" to array(obj(mapOf("tags" to tags)))))),
                    )
                )
            ),
            ElasticsearchIndexMapping.from(
                "test",
                TypeMapping.of { mapping ->
                    mapping.properties("deleted") { deleted -> deleted.boolean_ { it } }
                        .properties("notes") { notes -> notes.keyword { it } }
                        .properties("orders") { orders ->
                            orders.nested { ordersNested ->
                                ordersNested.properties("lines") { lines ->
                                    lines.nested { linesNested ->
                                        linesNested.properties("tags") { tags -> tags.keyword { it } }
                                    }
                                }
                            }
                        }
                }
            ),
        )

        assertThrows<QuerySchemaValidationException> {
            compileValidated(
                aggregation {
                    expand("orders")
                    expand("lines")
                    count("tagged") { "tags" eq "promo" }
                },
                bound,
            )
        }.message.assert().isEqualTo(
            "Aggregation metric filter field [orders.lines.tags] must be scalar; " +
                "array fields are not supported in metric filters.",
        )
        assertThrows<QuerySchemaValidationException> {
            compileValidated(aggregation { count("noted") { "notes" eq "premium" } }, bound)
        }.message.assert().isEqualTo(
            "Aggregation metric filter field [notes] must be scalar; " +
                "array fields are not supported in metric filters.",
        )
    }

    @Test
    fun `search filters in metric filters are rejected`() {
        val exception = assertThrows<QuerySchemaValidationException> {
            compileValidated(aggregation { count("hits") { "name" search "premium" } }, schema)
        }
        exception.message.assert().contains("do not support search filters")
        assertThrows<QuerySchemaValidationException> {
            compileValidated(aggregation { count("hits") { search("premium") } }, schema)
        }
    }

    @Test
    fun `metric plan models default to unfiltered`() {
        ElasticsearchAggregationMetric.Count("count").filter.assert().isNull()
        ElasticsearchAggregationMetric.Any("sample", "name").filter.assert().isNull()
        ElasticsearchAggregationMetric.Numeric("sum", AggregationFunction.SUM, "amount").filter.assert().isNull()
        ElasticsearchAggregationMetric.DistinctCount("customers", "customerId").filter.assert().isNull()
        ElasticsearchAggregationMetric.Percentile("p95", "amount", 95.0).filter.assert().isNull()
        ElasticsearchAggregationMetric.Derived(
            "aov",
            emptyMap(),
            Script.of { it.source { s -> s.scriptString("null") } },
        ).filter.assert().isNull()
    }

    @Test
    fun `every scalar filter leaf type compiles a non-null metric filter query`() {
        // Backends compile admitted input; admission normalizes it first.
        val query = QueryAdmission.Trusted.aggregate(
            aggregation {
                count("equal") { "deleted" eq false }
                count("notEqual") { "name" ne "Alpha" }
                count("inList") { "name" isIn listOf("Alpha", "Beta") }
                count("notInList") { "name" notIn listOf("Alpha") }
                count("greater") { "amount" gt 1 }
                count("greaterOrEqual") { "amount" gte 1 }
                count("less") { "amount" lt 9 }
                count("lessOrEqual") { "amount" lte 9 }
                count("between") { "amount".between(1, 5) }
                count("contains") { "name".containsText("Alp") }
                count("startsWith") { "name".startsWithText("Alp") }
                count("endsWith") { "name".endsWithText("pha") }
                count("nullName") { "name".isNull() }
                count("notNullName") { "name".isNotNull() }
                count("present") { "name".exists() }
                count("absent") { "name".notExists() }
                count("emptyString") { "name".isEmptyString() }
                count("notEmptyString") { "name".isNotEmptyString() }
                count("today") { "createdAt".today() }
            },
            schema,
        ).query
        val plan = compiler.compile(query, schema)

        plan.metrics.associateBy { it.alias }.forEach { (alias, metric) ->
            metric.filter.assert().isNotNull()
        }
        val metrics = plan.metrics.associateBy { it.alias }
        metrics.getValue("equal").filter!!.isTerm.assert().isTrue()
        metrics.getValue("greater").filter!!.isRange.assert().isTrue()
        metrics.getValue("inList").filter!!.isTerms.assert().isTrue()
        metrics.getValue("contains").filter!!.isWildcard.assert().isTrue()
        metrics.getValue("today").filter!!.isBool.assert().isTrue()
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
        val plan = compiler.compile(QueryAdmission.Trusted.aggregate(query, schema, now = now).query, schema)
        (listOf(plan.rootQuery) + plan.elements.map { it.filter }).forEach { filter ->
            filter.toString().assert().contains("86400000000").contains("172800000000")
        }
        plan.elements.forEach { it.filter.toString().assert().doesNotContain("deleted") }
        val next = compiler.compile(
            QueryAdmission.Trusted.aggregate(query, schema, now = now.plusSeconds(86400)).query,
            schema
        )
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
}
