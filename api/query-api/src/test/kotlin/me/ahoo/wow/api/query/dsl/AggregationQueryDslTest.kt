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

package me.ahoo.wow.api.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
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
    fun `group builders should expose bucket options`() {
        val terms = aggregation {
            terms("productId", "product", missingKey = "UNKNOWN")
            count("count")
        }
        (terms.groupBy.single() as AggregationGroup.Terms).missingKey.assert().isEqualTo("UNKNOWN")

        val days = aggregation {
            dateHistogram(
                "createdAt",
                AggregationDateUnit.DAY,
                "day",
                timeZone = ZoneId.of("Asia/Shanghai"),
                dense = true
            )
            count("count")
        }
        (days.groupBy.single() as AggregationGroup.DateHistogram).let { day ->
            day.timeZone.assert().isEqualTo("Asia/Shanghai")
            day.dense.assert().isTrue()
        }
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

    @Test
    fun `derived expression windows build window nodes`() {
        val month = Sort(QueryField("month"), Sort.Direction.ASC)
        val query = aggregation {
            terms("province", "province")
            dateHistogram("createdAt", AggregationDateUnit.MONTH, "month")
            sum("amount", "gmv")
            derived("share") { ref("gmv") / total("gmv", "province") }
            derived("cumulative") { running("gmv", month) }
            derived("moving") { running("gmv", month, listOf("province"), 3, RunningFunction.AVG) }
            derived("yoy") { previous("gmv", "month", period = AggregationDateUnit.YEAR) }
            derived("rank") { rank("gmv", "province", direction = Sort.Direction.ASC) }
        }

        query.metrics.filterIsInstance<AggregationMetric.Derived>().map { it.expression }.assert().containsExactly(
            DerivedExpression.Binary(
                AggregationExpressionOperator.DIVIDE,
                DerivedExpression.MetricRef("gmv"),
                DerivedExpression.Total("gmv", listOf("province")),
            ),
            DerivedExpression.Running("gmv", month),
            DerivedExpression.Running("gmv", month, listOf("province"), 3, RunningFunction.AVG),
            DerivedExpression.Previous("gmv", "month", period = AggregationDateUnit.YEAR),
            DerivedExpression.Rank("gmv", listOf("province"), Sort.Direction.ASC),
        )
    }

    @Test
    fun `query fields name groups, metrics and elements as strings do`() {
        val items = QueryField("state.items")
        val price = QueryField("price")
        val created = QueryField("createdAt")
        val byField = aggregation {
            filter(EqualFilter(QueryField("state.status"), "PAID"))
            expand(items)
            expand(QueryField("state.lines")) { QueryField("state.lines.quantity") gt 0 }
            terms(QueryField("sku"), "sku")
            histogram(price, 10.0, "price")
            dateHistogram(created, AggregationDateUnit.DAY, "day", ZoneId.of("UTC"))
            datePart(created, AggregationDatePart.HOUR_OF_DAY, "hour")
            any(QueryField("name"), "name")
            any(QueryField("name"), "paidName") { "status" eq "PAID" }
            first(QueryField("status"), "firstStatus", QueryField("version"))
            first(QueryField("status"), "firstPaid") { "status" eq "PAID" }
            last(QueryField("status"), "lastStatus")
            last(QueryField("status"), "lastPaid", QueryField("version")) { "status" eq "PAID" }
            sum(field(price), "sum")
            limit(10)
        }
        val byString = aggregation {
            filter { "state.status" eq "PAID" }
            expand("state.items")
            expand("state.lines") { "quantity" gt 0 }
            terms("sku", "sku")
            histogram("price", 10.0, "price")
            dateHistogram("createdAt", AggregationDateUnit.DAY, "day")
            datePart("createdAt", AggregationDatePart.HOUR_OF_DAY, "hour")
            any("name", "name")
            any("name", "paidName") { "status" eq "PAID" }
            first("status", "firstStatus", "version")
            first("status", "firstPaid") { "status" eq "PAID" }
            last("status", "lastStatus")
            last("status", "lastPaid", "version") { "status" eq "PAID" }
            sum("price", "sum")
            limit(10)
        }
        byField.assert().isEqualTo(byString)
        byField.elements[1].filter.assert().isEqualTo(GreaterThanFilter(QueryField("quantity"), 0))
    }

    @Test
    fun `groups take computed inputs, and an expression diffs two times`() {
        val total = AggregationExpression.Binary(
            AggregationExpressionOperator.MULTIPLY,
            AggregationExpression.Field(QueryField("price")),
            AggregationExpression.Field(QueryField("quantity")),
        )
        val lead = AggregationExpression.DateDiff(QueryField("paidAt"), QueryField("shippedAt"), DateDiffUnit.HOUR)
        val query = aggregation {
            terms(field("price") * field("quantity"), "band")
            histogram(field("price") * field("quantity"), 100.0, "total")
            max(dateDiff("paidAt", "shippedAt", DateDiffUnit.HOUR), "lead")
            min(dateDiff(QueryField("paidAt"), QueryField("shippedAt"), DateDiffUnit.HOUR), "minLead")
        }
        query.groupBy.assert().containsExactly(
            AggregationGroup.Terms(alias = "band", expression = total),
            AggregationGroup.Histogram(alias = "total", interval = 100.0, expression = total),
        )
        query.metrics.assert().containsExactly(
            AggregationMetric.Numeric(AggregationFunction.MAX, lead, "lead"),
            AggregationMetric.Numeric(AggregationFunction.MIN, lead, "minLead"),
        )
    }

    @Suppress("LongMethod")
    @Test
    fun `each optional argument of a group or metric may be left out or given`() {
        val day = QueryField("day")
        val shanghai = ZoneId.of("Asia/Shanghai")
        val query = aggregation {
            terms("a", "a")
            terms("b", "b", "none", AggregationGroupScope.ROOT)
            terms(QueryField("c"), "c")
            terms(field("d"), "d")
            terms(field("e"), "e", AggregationGroupScope.ELEMENT)
            histogram("f", 1.0, "f")
            histogram("g", 1.0, "g", AggregationGroupScope.ROOT)
            histogram(QueryField("h"), 1.0, "h")
            histogram(field("i"), 1.0, "i")
            histogram(field("j"), 1.0, "j", AggregationGroupScope.ELEMENT)
            dateHistogram("k", AggregationDateUnit.DAY, "k")
            dateHistogram("l", AggregationDateUnit.DAY, "l", shanghai)
            dateHistogram("m", AggregationDateUnit.DAY, "m", shanghai, scope = AggregationGroupScope.ROOT)
            dateHistogram(day, AggregationDateUnit.DAY, "n")
            dateHistogram(day, AggregationDateUnit.DAY, "o", shanghai, false, AggregationGroupScope.ROOT)
            datePart("p", AggregationDatePart.HOUR_OF_DAY, "p")
            datePart("q", AggregationDatePart.HOUR_OF_DAY, "q", shanghai)
            datePart("r", AggregationDatePart.HOUR_OF_DAY, "r", shanghai, scope = AggregationGroupScope.ROOT)
            datePart(day, AggregationDatePart.HOUR_OF_DAY, "s")
            datePart(day, AggregationDatePart.HOUR_OF_DAY, "t", shanghai, false, AggregationGroupScope.ROOT)
            count("count")
            first("status", "first1")
            first("status", "first2", "version")
            first("status", "first3") { "status" eq "PAID" }
            first("status", "first4", "version") { "status" eq "PAID" }
            first(QueryField("status"), "first5")
            first(QueryField("status"), "first6", QueryField("version")) { "status" eq "PAID" }
            last("status", "last1")
            last("status", "last2", "version")
            last("status", "last3") { "status" eq "PAID" }
            last("status", "last4", "version") { "status" eq "PAID" }
            last(QueryField("status"), "last5", QueryField("version"))
            last(QueryField("status"), "last6") { "status" eq "PAID" }
            avg("amount", "avg")
            min("amount", "min")
            max("amount", "max")
            derived("previous1") { previous("count", "k") }
            derived("previous2") { previous("count", "k", 2) }
            derived("previous3") { previous("count", "k", period = AggregationDateUnit.YEAR) }
            derived("rank1") { rank("count") }
            derived("rank2") { rank("count", "a", direction = Sort.Direction.ASC) }
            derived("running1") { running("count", Sort(QueryField("k"), Sort.Direction.ASC)) }
            derived("running2") {
                running("count", Sort(QueryField("k"), Sort.Direction.ASC), frame = 2, function = RunningFunction.AVG)
            }
            limit(1)
        }
        query.groupBy.assert().hasSize(20)
        query.metrics.assert().hasSize(23)
        (query.groupBy[0] as AggregationGroup.Terms).assert().isEqualTo(AggregationGroup.Terms(QueryField("a"), "a"))
        (query.groupBy[10] as AggregationGroup.DateHistogram).timeZone.assert().isEqualTo("UTC")
    }
}
