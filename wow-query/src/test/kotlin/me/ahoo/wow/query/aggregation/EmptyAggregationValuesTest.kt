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

package me.ahoo.wow.query.aggregation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.Test

class EmptyAggregationValuesTest {
    @Test
    fun `counts are zero and value metrics are null`() {
        val values = EmptyAggregationValues.values(
            listOf(
                AggregationMetric.Count("count"),
                AggregationMetric.Any(QueryField("name"), "any"),
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Field(QueryField("amount")),
                    "total",
                ),
                AggregationMetric.DistinctCount(
                    AggregationExpression.Field(QueryField("productId")),
                    "products",
                ),
                AggregationMetric.Percentile(
                    AggregationExpression.Field(QueryField("latency")),
                    95.0,
                    "p95",
                ),
            ),
        )
        values["count"].assert().isEqualTo(0L)
        values["any"].assert().isNull()
        values["total"].assert().isNull()
        values["products"].assert().isEqualTo(0L)
        values["p95"].assert().isNull()
    }

    @Test
    fun `derived metrics evaluate over empty values in declaration order`() {
        val values = EmptyAggregationValues.values(
            listOf(
                AggregationMetric.Count("count"),
                AggregationMetric.Derived("aov", div("count")),
                AggregationMetric.Derived("scaled", constantTimesTwo()),
            ),
        )
        values["aov"].assert().isNull() // 0 divided by 0 -> null
        values["scaled"].assert().isEqualTo(2.0)
    }

    @Test
    fun `derived metrics propagate null operands and non finite results to null`() {
        val values = EmptyAggregationValues.values(
            listOf(
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Field(QueryField("amount")),
                    "total",
                ),
                AggregationMetric.Derived("plusNull", plusOne("total")),
                AggregationMetric.Derived("overflow", doubleOverflow()),
            ),
        )
        // A null operand (empty SUM) nullifies the whole expression.
        values["plusNull"].assert().isNull()
        // Double overflow yields a non-finite result, which collapses to null.
        values["overflow"].assert().isNull()
    }

    @Test
    fun `derived metrics evaluate every operator and propagate a null right operand`() {
        val values = EmptyAggregationValues.values(
            listOf(
                AggregationMetric.Count("count"),
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Field(QueryField("amount")),
                    "total",
                ),
                AggregationMetric.Derived("countPlusOne", add(ref("count"), constant(1.0))),
                AggregationMetric.Derived("difference", subtract(constant(2.0), ref("count"))),
                AggregationMetric.Derived("ratio", div(constant(6.0), constant(3.0))),
                AggregationMetric.Derived("plusRightNull", add(constant(1.0), ref("total"))),
            ),
        )
        values["countPlusOne"].assert().isEqualTo(1.0)
        values["difference"].assert().isEqualTo(2.0)
        // A non-zero divisor divides through instead of collapsing to null.
        values["ratio"].assert().isEqualTo(2.0)
        // Only the RIGHT operand is null here: the expression still nullifies.
        values["plusRightNull"].assert().isNull()
    }

    private fun div(metric: String) = DerivedExpression.Binary(
        AggregationExpressionOperator.DIVIDE,
        DerivedExpression.MetricRef(metric),
        DerivedExpression.MetricRef(metric),
    )

    private fun constantTimesTwo() = DerivedExpression.Binary(
        AggregationExpressionOperator.MULTIPLY,
        DerivedExpression.Constant(1.0),
        DerivedExpression.Constant(2.0),
    )

    private fun plusOne(metric: String) = DerivedExpression.Binary(
        AggregationExpressionOperator.ADD,
        DerivedExpression.MetricRef(metric),
        DerivedExpression.Constant(1.0),
    )

    private fun div(left: DerivedExpression, right: DerivedExpression) = DerivedExpression.Binary(
        AggregationExpressionOperator.DIVIDE,
        left,
        right,
    )

    private fun add(left: DerivedExpression, right: DerivedExpression) = DerivedExpression.Binary(
        AggregationExpressionOperator.ADD,
        left,
        right,
    )

    private fun subtract(left: DerivedExpression, right: DerivedExpression) = DerivedExpression.Binary(
        AggregationExpressionOperator.SUBTRACT,
        left,
        right,
    )

    private fun ref(metric: String) = DerivedExpression.MetricRef(metric)

    private fun constant(value: Double) = DerivedExpression.Constant(value)

    private fun doubleOverflow() = DerivedExpression.Binary(
        AggregationExpressionOperator.MULTIPLY,
        DerivedExpression.Constant(Double.MAX_VALUE),
        DerivedExpression.Constant(2.0),
    )
}
