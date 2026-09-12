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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.Test
import java.time.ZoneId

class AggregationQueryDslTest {

    @Test
    fun `aggregation DSL should preserve relative scopes and explicit aliases`() {
        val query = aggregation {
            filter { "state.status" eq "COMPLETED" }
            expand("state.orders") { "status" eq "PAID" }
            expand("lines") { "quantity" gt 0 }
            expand("discounts")
            terms("productId", alias = "product")
            count(alias = "count")
            sum("amount", alias = "total")
            sort { "total".desc() }
            limit(20)
        }

        query.elements.map { it.path.path }.assert().containsExactly("state.orders", "lines", "discounts")
        query.groupBy.assert().hasSize(1)
        query.metrics.assert().hasSize(2)
        query.limit.assert().isEqualTo(20)
    }

    @Test
    fun `aggregation DSL should map histogram positional arguments`() {
        val query = aggregation {
            histogram("amount", 50.0, "amountBucket")
            dateHistogram("createdAt", AggregationDateUnit.DAY, "day", ZoneId.of("Asia/Shanghai"))
            count("count")
        }

        query.groupBy.assert().containsExactly(
            AggregationGroup.Histogram(QueryField("amount"), "amountBucket", 50.0),
            AggregationGroup.DateHistogram(
                QueryField("createdAt"),
                "day",
                AggregationDateUnit.DAY,
                "Asia/Shanghai",
            ),
        )
    }

    @Test
    fun `aggregation DSL should add an any metric without another group`() {
        val query = aggregation {
            terms("productId", "productId")
            any("productName", "productName")
            count("count")
        }

        query.groupBy.assert().containsExactly(
            AggregationGroup.Terms(QueryField("productId"), "productId"),
        )
        query.metrics.assert().containsExactly(
            AggregationMetric.Any(QueryField("productName"), "productName"),
            AggregationMetric.Count("count"),
        )
    }

    @Test
    fun `aggregation DSL should build arithmetic metric expressions`() {
        val query = aggregation {
            sum(field("price") * field("quantity") - constant(10.0), "total")
            avg(field("amount") / constant(2.0) + field("fee"), "average")
        }

        query.metrics.assert().containsExactly(
            AggregationMetric.Numeric(
                AggregationFunction.SUM,
                AggregationExpression.Binary(
                    AggregationExpressionOperator.SUBTRACT,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.MULTIPLY,
                        AggregationExpression.Field(QueryField("price")),
                        AggregationExpression.Field(QueryField("quantity")),
                    ),
                    AggregationExpression.Constant(10.0),
                ),
                "total",
            ),
            AggregationMetric.Numeric(
                AggregationFunction.AVG,
                AggregationExpression.Binary(
                    AggregationExpressionOperator.ADD,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        AggregationExpression.Field(QueryField("amount")),
                        AggregationExpression.Constant(2.0),
                    ),
                    AggregationExpression.Field(QueryField("fee")),
                ),
                "average",
            ),
        )
    }

    @Test
    fun `aggregation DSL should map new metric functions`() {
        val query = aggregation {
            terms("status", "status")
            distinctCount("customerId", "customers")
            stddev("amount", "amtStddev")
            variance("amount", "amtVariance")
            percentile("amount", 95.0, "amtP95")
            median("amount", "amtMedian")
        }

        query.metrics.assert().containsExactly(
            AggregationMetric.DistinctCount(
                AggregationExpression.Field(QueryField("customerId")),
                "customers",
            ),
            AggregationMetric.Numeric(
                AggregationFunction.STDDEV,
                AggregationExpression.Field(QueryField("amount")),
                "amtStddev",
            ),
            AggregationMetric.Numeric(
                AggregationFunction.VARIANCE,
                AggregationExpression.Field(QueryField("amount")),
                "amtVariance",
            ),
            AggregationMetric.Percentile(
                AggregationExpression.Field(QueryField("amount")),
                95.0,
                "amtP95",
            ),
            AggregationMetric.Percentile(
                AggregationExpression.Field(QueryField("amount")),
                50.0,
                "amtMedian",
            ),
        )
    }

    @Test
    fun `aggregation DSL should apply metric filters`() {
        val query = aggregation {
            terms("status", "status")
            count("paid") { "status" eq "PAID" }
            sum("amount", "paidAmount") { "status" eq "PAID" }
            distinctCount(field("customerId"), "customers") { "amount" gt 0 }
            percentile("amount", 95.0, "p95") { "status" eq "PAID" }
        }

        query.metrics.filterIsInstance<AggregationMetric.Count>().single().filter.assert()
            .isInstanceOf(EqualFilter::class.java)
        query.metrics.filterIsInstance<AggregationMetric.Numeric>().single().filter.assert()
            .isInstanceOf(EqualFilter::class.java)
        query.metrics.filterIsInstance<AggregationMetric.DistinctCount>().single().filter.assert()
            .isInstanceOf(GreaterThanFilter::class.java)
        query.metrics.filterIsInstance<AggregationMetric.Percentile>().single().filter.assert()
            .isInstanceOf(EqualFilter::class.java)
    }

    @Test
    fun `aggregation DSL should apply metric filters to every metric overload`() {
        val query = aggregation {
            terms("status", "status")
            any("productName", "anyName") { "status" eq "PAID" }
            avg("amount", "avgAmount") { "status" eq "PAID" }
            min("amount", "minAmount") { "status" eq "PAID" }
            max("amount", "maxAmount") { "status" eq "PAID" }
            stddev("amount", "stddevAmount") { "status" eq "PAID" }
            variance("amount", "varianceAmount") { "status" eq "PAID" }
            median("amount", "medianAmount") { "amount" gt 0 }
            sum(field("amount"), "sumExprAmount") { "status" eq "PAID" }
            avg(field("amount"), "avgExprAmount") { "status" eq "PAID" }
            min(field("amount"), "minExprAmount") { "status" eq "PAID" }
            max(field("amount"), "maxExprAmount") { "status" eq "PAID" }
            stddev(field("amount"), "stddevExprAmount") { "status" eq "PAID" }
            variance(field("amount"), "varianceExprAmount") { "status" eq "PAID" }
            distinctCount("customerId", "fieldCustomers") { "amount" gt 0 }
            percentile(field("amount"), 95.0, "exprP95") { "status" eq "PAID" }
            median(field("amount"), "exprMedian") { "amount" gt 0 }
        }

        val metricsByAlias = query.metrics.associateBy { it.alias }
        metricsByAlias.keys.assert().hasSize(16)
        val greaterThanAliases = setOf("medianAmount", "fieldCustomers", "exprMedian")
        metricsByAlias.values.forEach { metric ->
            val expectedType = if (metric.alias in greaterThanAliases) {
                GreaterThanFilter::class.java
            } else {
                EqualFilter::class.java
            }
            metric.filter.assert().isInstanceOf(expectedType)
        }
        query.metrics.filterIsInstance<AggregationMetric.Percentile>()
            .map(AggregationMetric.Percentile::percentile)
            .assert()
            .containsExactly(50.0, 95.0, 50.0)
    }

    @Test
    fun `aggregation DSL should build derived metrics from refs and operators`() {
        val query = aggregation {
            count("paid")
            sum("amount", "paidAmount") { "status" eq "PAID" }
            derived("aov") { ref("paidAmount") / ref("paid") }
            derived("target") { constant(120.0) }
            derived("attainment") { ref("paidAmount") / ref("target") }
        }

        val derived = query.metrics.filterIsInstance<AggregationMetric.Derived>()
        derived.assert().hasSize(3)
        val aov = derived[0].expression as DerivedExpression.Binary
        aov.operator.assert().isEqualTo(AggregationExpressionOperator.DIVIDE)
        (aov.left as DerivedExpression.MetricRef).metric.assert().isEqualTo("paidAmount")
        (aov.right as DerivedExpression.MetricRef).metric.assert().isEqualTo("paid")
        (derived[1].expression as DerivedExpression.Constant).value.assert().isEqualTo(120.0)
        val attainment = derived[2].expression as DerivedExpression.Binary
        (attainment.left as DerivedExpression.MetricRef).metric.assert().isEqualTo("paidAmount")
        (attainment.right as DerivedExpression.MetricRef).metric.assert().isEqualTo("target")
    }

    @Test
    fun `aggregation DSL should accept a prebuilt derived expression`() {
        val expression = DerivedExpression.Binary(
            AggregationExpressionOperator.SUBTRACT,
            DerivedExpression.MetricRef("total"),
            DerivedExpression.Constant(1.0),
        )
        val query = aggregation {
            count("total")
            derived("net", expression)
        }

        query.metrics.assert().containsExactly(
            AggregationMetric.Count("total"),
            AggregationMetric.Derived("net", expression),
        )
    }

    @Test
    fun `aggregation DSL should build having expressions`() {
        val query = aggregation {
            terms("state.status", "status")
            count("paid")
            sum("amount", "paidAmount") { "state.status" eq "PAID" }
            derived("attainment") { ref("paidAmount") / constant(6000.0) }
            having {
                ("attainment" gte 0.8) and ("paid" gt 10.0)
            }
            sort { "attainment".desc() }
        }

        val having = requireNotNull(query.having) as HavingExpression.And
        (having.operands[0] as HavingExpression.Condition).let {
            it.metric.assert().isEqualTo("attainment")
            it.operator.assert().isEqualTo(ComparisonOperator.GTE)
            it.value.assert().isEqualTo(0.8)
        }
        (having.operands[1] as HavingExpression.Condition).let {
            it.metric.assert().isEqualTo("paid")
            it.operator.assert().isEqualTo(ComparisonOperator.GT)
            it.value.assert().isEqualTo(10.0)
        }
    }

    @Test
    fun `aggregation DSL having supports the full operator set`() {
        val comparisons = aggregation {
            terms("state.status", "status")
            count("c")
            having {
                ((("c" eq 1.0) and ("c" ne 2.0)) and (("c" gt 3.0) and ("c" gte 4.0))) and
                    (("c" lt 5.0) and ("c" lte 6.0))
            }
        }
        fun HavingExpression.conditions(): List<HavingExpression.Condition> = when (this) {
            is HavingExpression.Condition -> listOf(this)
            is HavingExpression.And -> operands.flatMap { it.conditions() }
            else -> throw AssertionError("Unexpected having operand: $this")
        }
        val leaves = requireNotNull(comparisons.having).conditions()
        leaves.map(HavingExpression.Condition::metric).assert().containsOnly("c")
        leaves.map(HavingExpression.Condition::operator).assert().containsExactly(
            ComparisonOperator.EQ,
            ComparisonOperator.NE,
            ComparisonOperator.GT,
            ComparisonOperator.GTE,
            ComparisonOperator.LT,
            ComparisonOperator.LTE,
        )
        leaves.map(HavingExpression.Condition::value).assert().containsExactly(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)

        val leavesQuery = aggregation {
            terms("state.status", "status")
            count("c")
            having {
                ("c".between(1.0, 2.0) or "c".isIn(listOf(3.0, 4.0))) or
                    ("c".isNull() or "c".isNotNull())
            }
        }
        val or = requireNotNull(leavesQuery.having) as HavingExpression.Or
        val left = or.operands[0] as HavingExpression.Or
        val between = left.operands[0] as HavingExpression.Between
        between.metric.assert().isEqualTo("c")
        between.lower.assert().isEqualTo(1.0)
        between.upper.assert().isEqualTo(2.0)
        val isIn = left.operands[1] as HavingExpression.In
        isIn.metric.assert().isEqualTo("c")
        isIn.values.assert().containsExactly(3.0, 4.0)
        val right = or.operands[1] as HavingExpression.Or
        (right.operands[0] as HavingExpression.IsNull).let {
            it.metric.assert().isEqualTo("c")
            it.negated.assert().isFalse()
        }
        (right.operands[1] as HavingExpression.IsNull).let {
            it.metric.assert().isEqualTo("c")
            it.negated.assert().isTrue()
        }
    }

    @Test
    fun `derived expression operators build binary nodes for every operator`() {
        val query = aggregation {
            count("a")
            count("b")
            derived("plus") { ref("a") + ref("b") }
            derived("minus") { ref("a") - ref("b") }
            derived("times") { ref("a") * ref("b") }
            derived("div") { ref("a") / ref("b") }
        }

        val derived = query.metrics.filterIsInstance<AggregationMetric.Derived>()
        derived.assert().hasSize(4)
        val operators = derived.map { (it.expression as DerivedExpression.Binary).operator }
        operators.assert().containsExactly(
            AggregationExpressionOperator.ADD,
            AggregationExpressionOperator.SUBTRACT,
            AggregationExpressionOperator.MULTIPLY,
            AggregationExpressionOperator.DIVIDE,
        )
    }
}
