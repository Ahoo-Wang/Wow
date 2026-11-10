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

import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.dsl.aggregation
import me.ahoo.wow.api.query.fields
import me.ahoo.wow.elasticsearch.query.ElasticsearchFilterCompiler
import me.ahoo.wow.elasticsearch.query.compile
import org.junit.jupiter.api.Test

/** The scripts the compiler emits: date differences, runtime and derived arithmetic, their guards and size limit. */
internal class ElasticsearchAggregationScriptsTest : ElasticsearchAggregationCompilerFixtures() {
    @Test
    fun `date differences run as runtime scripts in metrics, groups and filters`() {
        val hours = me.ahoo.wow.api.query.AggregationExpression.DateDiff(
            QueryField("createdAt"),
            QueryField("orders.createdAt"),
            me.ahoo.wow.api.query.DateDiffUnit.HOUR,
        )
        val sameScope = me.ahoo.wow.api.query.AggregationExpression.DateDiff(
            QueryField("createdAt"),
            QueryField("createdAt"),
            me.ahoo.wow.api.query.DateDiffUnit.DAY,
        )
        val plan = compiler.compile(
            AggregationQuery(
                groupBy = listOf(
                    me.ahoo.wow.api.query.AggregationGroup.Histogram(
                        alias = "bucket",
                        interval = 1.0,
                        expression = sameScope
                    ),
                ),
                metrics = listOf(
                    me.ahoo.wow.api.query.AggregationMetric.Numeric(AggregationFunction.AVG, sameScope, "avgDays"),
                ),
            ),
            schema,
        )
        plan.groupSources.single().value().histogram().field().assert().isEqualTo("__wow_group_expression_0")
        val group = requireNotNull(plan.runtimeMappings.getValue("__wow_group_expression_0").script())
        requireNotNull(
            group.source()
        ).scriptString().assert()
            // epoch micros decode as the date groups decode them: floored to whole milliseconds, never fractional
            .contains("doc.containsKey", "size() == 1", "numeric == (double) epoch", "Long.MAX_VALUE")
            .contains("((Number) params.d0).longValue()", "((Number) params.m0).longValue()")
            .doesNotContain("doubleValue() * ")
        group.params().values.map { it.to(Any::class.java) }.assert().contains("createdAt", 1L, 1_000L, 86_400_000L)
        (plan.metrics.single() as ElasticsearchAggregationMetric.Numeric).field.assert()
            .isEqualTo("__wow_expression_0")

        val filter = ElasticsearchFilterCompiler.compile(
            me.ahoo.wow.api.query.ExpressionFilter(sameScope, me.ahoo.wow.api.query.ComparisonOperator.GTE, 2.0),
            schema,
        )
        requireNotNull(filter.script().script().source()).scriptString().assert()
            .contains("return ", " >= ((Number)params.__value).doubleValue()")
        hours.fields.assert().hasSize(2)
    }

    @Test
    fun `non-field distinct count and percentile expressions compile to runtime fields`() {
        val plan = ElasticsearchAggregationCompiler.compile(
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
    fun `derived arithmetic keeps its NaN sentinel script for every operator`() {
        val plan = compiler.compile(
            aggregation {
                count("total")
                sum("amount", "paid")
                derived("mixed") { (ref("paid") + constant(1.0)) * ref("total") - constant(3.0) / ref("paid") }
            },
            schema,
        )
        val mixed = plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Derived>().single()
        requireNotNull(mixed.script.source()).scriptString().assert().isEqualTo(
            "def value = ((((((double) params.c0) == 0.0 ? Double.NaN : ((double) params.v0)) + 1.0) * " +
                "((double) params.v1)) - (3.0 / (((double) params.c0) == 0.0 ? Double.NaN : ((double) params.v0)))); " +
                "value == null || !Double.isFinite(value) ? null : value",
        )
    }

    @Test
    fun `runtime arithmetic keeps its null guarded script for every operator`() {
        val amount = AggregationExpression.Field(QueryField("amount"))
        val two = AggregationExpression.Constant(2.0)
        fun binary(operator: AggregationExpressionOperator, left: AggregationExpression, right: AggregationExpression) =
            AggregationExpression.Binary(operator, left, right)
        val expression = binary(
            AggregationExpressionOperator.SUBTRACT,
            binary(
                AggregationExpressionOperator.MULTIPLY,
                binary(AggregationExpressionOperator.ADD, amount, two),
                amount
            ),
            binary(AggregationExpressionOperator.DIVIDE, two, amount),
        )
        val plan = compiler.compile(aggregation { sum(expression, "total") }, schema)
        plan.runtimeMappings.values.single().script()!!.source()!!.scriptString().assert().isEqualTo(
            "def v0=null;String f0=params.f0;if(doc.containsKey(f0)&&doc[f0].size() == 1){" +
                "def r0=doc[f0].value;if (r0 instanceof Number) {double c0=((Number)r0).doubleValue();" +
                "if(Double.isFinite(c0)){v0=c0;}}}" +
                "def v1=((Number)params.n1).doubleValue();" +
                "def v2=null;if (v0 != null && v1 != null) {" +
                "double c2=v0.doubleValue() + v1.doubleValue();if(Double.isFinite(c2)){v2=c2;}}" +
                "def v3=null;String f3=params.f3;if(doc.containsKey(f3)&&doc[f3].size() == 1){" +
                "def r3=doc[f3].value;if (r3 instanceof Number) {double c3=((Number)r3).doubleValue();" +
                "if(Double.isFinite(c3)){v3=c3;}}}" +
                "def v4=null;if (v2 != null && v3 != null) {" +
                "double c4=v2.doubleValue() * v3.doubleValue();if(Double.isFinite(c4)){v4=c4;}}" +
                "def v5=((Number)params.n5).doubleValue();" +
                "def v6=null;String f6=params.f6;if(doc.containsKey(f6)&&doc[f6].size() == 1){" +
                "def r6=doc[f6].value;if (r6 instanceof Number) {double c6=((Number)r6).doubleValue();" +
                "if(Double.isFinite(c6)){v6=c6;}}}" +
                "def v7=null;if (v5 != null && v6 != null && v6.doubleValue() != 0.0) {" +
                "double c7=v5.doubleValue() / v6.doubleValue();if(Double.isFinite(c7)){v7=c7;}}" +
                "def v8=null;if (v4 != null && v7 != null) {" +
                "double c8=v4.doubleValue() - v7.doubleValue();if(Double.isFinite(c8)){v8=c8;}}" +
                "if (v8 != null) { emit(v8.doubleValue()); }",
        )
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
}
