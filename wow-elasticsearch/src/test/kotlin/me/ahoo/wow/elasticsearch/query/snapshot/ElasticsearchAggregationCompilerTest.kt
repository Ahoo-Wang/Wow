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

import co.elastic.clients.elasticsearch._types.Script
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.HavingExpression
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

@Suppress("LargeClass")
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
                "tenantId" to text,
                "ownerId" to text,
                "spaceId" to text,
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
    fun `terms missingKey routes the source through a keyword runtime field`() {
        val plan = compiler.compile(
            aggregation {
                terms("name", "name", missingKey = "UNKNOWN")
                count("count")
            },
            schema,
        )
        val runtime = plan.runtimeMappings.getValue("__wow_missing_terms_0")
        runtime.type().assert().isEqualTo(RuntimeFieldType.Keyword)
        val script = requireNotNull(runtime.script())
        script.params().values.map { it.to(Any::class.java) }.assert().contains("name.keyword", "UNKNOWN")
        requireNotNull(script.source()).scriptString().assert()
            .contains("doc.containsKey")
            .contains("size() == 1")
            .contains("emit(")
            .contains("params.missing")
        plan.groupSources.single().value().terms().field().assert().isEqualTo("__wow_missing_terms_0")
    }

    @Test
    fun `dense date histogram should carry the dense bucket plan`() {
        val plan = compiler.compile(
            aggregation {
                dateHistogram("createdAt", AggregationDateUnit.DAY, "day", dense = true)
                count("count")
            },
            schema,
        )
        val dense = requireNotNull(plan.dense)
        dense.alias.assert().isEqualTo("day")
        // the plan carries the ORIGINAL API metrics so empty-value evaluation sees declaration order
        dense.metrics.assert().containsExactly(AggregationMetric.Count("count"))
        compiler.compile(
            aggregation {
                dateHistogram("createdAt", AggregationDateUnit.DAY, "day")
                count("count")
            },
            schema,
        ).dense.assert().isNull()
    }

    @Test
    fun `terms without missingKey keeps the physical field`() {
        val plan = compiler.compile(
            aggregation {
                terms("name", "name")
                count("count")
            },
            schema,
        )
        plan.groupSources.single().value().terms().field().assert().isEqualTo("name.keyword")
        plan.runtimeMappings.containsKey("__wow_missing_terms_0").assert().isFalse()
        plan.runtimeMappings.assert().isEmpty()
    }

    @Test
    fun `plan should carry the having expression`() {
        val plan = compiler.compile(
            aggregation {
                terms("name", "name")
                count("c")
                having { "c" gte 2.0 }
            },
            schema,
        )
        val having = plan.having as HavingExpression.Condition
        having.metric.assert().isEqualTo("c")
        having.operator.assert().isEqualTo(ComparisonOperator.GTE)
        having.value.assert().isEqualTo(2.0)
        compiler.compile(aggregation { count("c") }, schema).having.assert().isNull()
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
            compiler.compile(
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
            compiler.compile(aggregation { count("noted") { "notes" eq "premium" } }, bound)
        }.message.assert().isEqualTo(
            "Aggregation metric filter field [notes] must be scalar; " +
                "array fields are not supported in metric filters.",
        )
    }

    @Test
    fun `search filters in metric filters are rejected`() {
        val exception = assertThrows<QuerySchemaValidationException> {
            compiler.compile(aggregation { count("hits") { "name" search "premium" } }, schema)
        }
        exception.message.assert().contains("do not support search filters")
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(aggregation { count("hits") { search("premium") } }, schema)
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
        val plan = compiler.compile(
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
                count("allTerms") { "customerId" containsAll listOf("premium") }
                count("emptyCollection") { "name".isEmptyCollection() }
                count("today") { "createdAt".today() }
            },
            schema,
        )

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
    fun `metadata and logical metric filters compile scoped queries`() {
        val plan = compiler.compile(
            aggregation {
                count("byId") { id("order-1") }
                count("byIds") { ids("order-1", "order-2") }
                count("byAggregateId") { aggregateId("order-1") }
                count("byAggregateIds") { aggregateIds("order-1", "order-2") }
                count("byTenant") { tenantId("tenant-1") }
                count("byOwner") { ownerId("owner-1") }
                count("bySpace") { spaceId("space-1") }
                count("active") { deletion(DeletionState.ACTIVE) }
                count("anyState") { deletion(DeletionState.ALL) }
                count("none") { matchNone() }
                count("composed") {
                    and {
                        "deleted" eq false
                        "amount" gt 0
                    }
                }
                count("either") {
                    or {
                        "deleted" eq false
                        "amount" gt 0
                    }
                }
                count("neither") {
                    nor {
                        "deleted" eq false
                        "amount" gt 0
                    }
                }
                count("nested") {
                    and {
                        or {
                            "deleted" eq false
                            "name".exists()
                        }
                    }
                }
            },
            schema,
        )

        val metrics = plan.metrics.associateBy { it.alias }
        metrics.forEach { (alias, metric) ->
            metric.filter.assert().isNotNull()
        }
        metrics.getValue("byIds").filter!!.isIds.assert().isTrue()
        metrics.getValue("none").filter!!.isMatchNone.assert().isTrue()
        metrics.getValue("anyState").filter!!.isMatchAll.assert().isTrue()
        metrics.getValue("composed").filter!!.isBool.assert().isTrue()

        assertThrows<QuerySchemaValidationException> {
            compiler.compile(aggregation { count("unknown") { "unknown" eq "value" } }, schema)
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
    fun `derived metrics plan bucket scripts with guarded paths`() {
        val plan = compiler.compile(
            aggregation {
                count("paid") { "deleted" eq false }
                sum("amount", "paidAmount") { "deleted" eq false }
                sum("amount", "totalAmount")
                derived("aov") { ref("paidAmount") / ref("paid") }
                derived("attainment") { ref("paidAmount") / ref("totalAmount") }
            },
            schema,
        )
        val derived = plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Derived>()
        derived.assert().hasSize(2)
        val aov = derived[0]
        // filtered Numeric: value via the metric filter wrapper, count via the wrapper's value count;
        // wrapper traversal uses the '>' separator — a dotted path would look up a sibling literally
        // named "__wow_metric_filter_paidAmount.paidAmount" and fail request validation;
        // filtered Count: alias._count
        aov.bucketsPath.assert().containsKey("v0").containsKey("c0").containsKey("v1")
        aov.bucketsPath["v0"].assert().isEqualTo("__wow_metric_filter_paidAmount>paidAmount.value")
        aov.bucketsPath["c0"].assert().isEqualTo("__wow_metric_filter_paidAmount>__wow_value_count_paidAmount.value")
        aov.bucketsPath["v1"].assert().isEqualTo("paid._count")
        requireNotNull(aov.script.source()).scriptString().assert()
            // NaN sentinel: an empty-set sum is a value (0.0), not a gap, so the guard must stay
            // in-double-arithmetic (Painless throws on null operands) and let NaN flow to the final wrap;
            // casts use the Painless `(double)` form — Painless has no `as` cast operator
            .contains("(((double) params.c0) == 0.0 ? Double.NaN : ((double) params.v0))")
            .contains("((double) params.v1)")
            .contains("Double.NaN")
            .contains("Double.isFinite")
            .doesNotContain("== 0.0 ? null")
            .doesNotContain(" as double")
        val attainment = derived[1]
        attainment.bucketsPath["v0"].assert().isEqualTo("__wow_metric_filter_paidAmount>paidAmount.value")
        attainment.bucketsPath["v2"].assert().isEqualTo("totalAmount.value") // unfiltered Numeric has no wrapper
        attainment.bucketsPath["c2"].assert().isEqualTo("__wow_value_count_totalAmount.value")
        requireNotNull(attainment.script.source()).scriptString().assert().contains("Double.isFinite")
    }

    @Test
    fun `derived chains reference prior derived aliases`() {
        val plan = compiler.compile(
            aggregation {
                count("total")
                derived("half") { ref("total") / constant(2.0) }
                derived("quarter") { ref("half") / constant(2.0) }
            },
            schema,
        )
        val quarter = plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Derived>()[1]
        quarter.bucketsPath.values.single().assert().isEqualTo("half.value")
        requireNotNull(quarter.script.source()).scriptString().assert()
            .contains("((double) params.v1)")
            .contains("/ 2.0")
    }

    @Test
    fun `derived metrics reference percentile distinct and deviation paths`() {
        val plan = compiler.compile(
            aggregation {
                percentile("amount", 95.0, "p95")
                distinctCount("customerId", "customers")
                stddev("amount", "deviation")
                variance("amount", "spread")
                count("total")
                derived("sharpness") { ref("p95") / ref("deviation") }
                derived("spreadPerCustomer") { ref("spread") - ref("customers") }
                derived("mixed") { (ref("total") + constant(1.0)) * constant(2.0) - constant(3.0) }
            },
            schema,
        )
        val derived = plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Derived>()

        val sharpness = derived[0]
        sharpness.bucketsPath["v0"].assert().isEqualTo("p95[95.0]")
        sharpness.bucketsPath["c0"].assert().isEqualTo("__wow_value_count_p95.value")
        sharpness.bucketsPath["v1"].assert().isEqualTo("deviation.std_deviation_population")
        sharpness.bucketsPath["c1"].assert().isEqualTo("__wow_value_count_deviation.value")
        requireNotNull(sharpness.script.source()).scriptString().assert().contains("/ ")

        val spreadPerCustomer = derived[1]
        // 引用编号为编译级首现共享：sharpness 已占用 v0/c0、v1/c1
        spreadPerCustomer.bucketsPath["v2"].assert().isEqualTo("spread.variance_population")
        spreadPerCustomer.bucketsPath["c2"].assert().isEqualTo("__wow_value_count_spread.value")
        spreadPerCustomer.bucketsPath["v3"].assert().isEqualTo("customers.value")
        spreadPerCustomer.bucketsPath.assert().doesNotContainKey("c3") // cardinality 恒非 null，无需空语义守卫
        requireNotNull(spreadPerCustomer.script.source()).scriptString().assert().contains(" - ")

        val mixed = derived[2]
        mixed.bucketsPath.values.single().assert().isEqualTo("_count")
        requireNotNull(mixed.script.source()).scriptString().assert()
            .contains(" + 1.0")
            .contains(" * 2.0")
            .contains(" - 3.0")
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
