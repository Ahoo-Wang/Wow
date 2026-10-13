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

package me.ahoo.wow.query.spec

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.QueryCapability
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The metric table (design §6.1), row by row: a change here is a change to what every metric requires. */
class MetricSpecTest {
    private val field = QueryField("state.amount")
    private val input = AggregationExpression.Field(field)
    private val arithmetic = AggregationExpression.Binary(AggregationExpressionOperator.ADD, input, input)

    private val samples = listOf(
        AggregationMetric.Count("m"),
        AggregationMetric.Numeric(AggregationFunction.SUM, input, "m"),
        AggregationMetric.Any(field, "m"),
        AggregationMetric.DistinctCount(input, "m"),
        AggregationMetric.Percentile(input, 50.0, "m"),
        AggregationMetric.Derived("m", DerivedExpression.MetricRef("n")),
        AggregationMetric.First(field, "m"),
        AggregationMetric.Last(field, "m"),
    )

    @Test
    fun `every metric type has exactly one spec`() {
        samples.map { it.spec }.assert().containsExactlyElementsOf(MetricSpec.entries)
        samples.forEach { MetricSpec.of(it).assert().isEqualTo(it.spec) }
    }

    @Test
    fun `inputs, capabilities, results and having follow the table`() {
        val terms = QueryCapability.AGGREGATE_TERMS
        val numeric = QueryCapability.AGGREGATE_NUMERIC
        mapOf(
            MetricSpec.COUNT to Row(MetricInput.NONE, emptyList(), MetricResult.COUNT, having = true),
            MetricSpec.NUMERIC to Row(MetricInput.EXPRESSION, listOf(numeric), MetricResult.NUMBER, having = true),
            MetricSpec.ANY to Row(MetricInput.SINGLE_VALUE_FIELD, listOf(terms), MetricResult.FIELD_VALUE, having = false),
            MetricSpec.DISTINCT_COUNT to Row(
                MetricInput.EXPRESSION,
                listOf(terms, numeric),
                MetricResult.COUNT,
                having = true
            ),
            MetricSpec.PERCENTILE to Row(MetricInput.EXPRESSION, listOf(numeric), MetricResult.NUMBER, having = true),
            MetricSpec.DERIVED to Row(MetricInput.NONE, emptyList(), MetricResult.NUMBER, having = true),
            MetricSpec.FIRST to Row(
                MetricInput.SINGLE_VALUE_FIELD,
                listOf(terms, numeric),
                MetricResult.FIELD_VALUE,
                having = false,
                order = QueryCapability.SORT,
            ),
            MetricSpec.LAST to Row(
                MetricInput.SINGLE_VALUE_FIELD,
                listOf(terms, numeric),
                MetricResult.FIELD_VALUE,
                having = false,
                order = QueryCapability.SORT,
            ),
        ).forEach { (spec, row) ->
            Row(spec.input, spec.fieldCapabilities, spec.result, spec.havingOperand, spec.orderCapability)
                .assert().describedAs(spec.name).isEqualTo(row)
        }
    }

    @Test
    fun `derived, first and last are expensive, and so is any arithmetic input`() {
        samples.associate { it.spec to it.spec.cost(it) }.filterValues { it == OperatorCost.EXPENSIVE }.keys
            .assert().containsExactlyInAnyOrder(MetricSpec.DERIVED, MetricSpec.FIRST, MetricSpec.LAST)
        listOf(
            AggregationMetric.Numeric(AggregationFunction.SUM, arithmetic, "m"),
            AggregationMetric.DistinctCount(arithmetic, "m"),
            AggregationMetric.Percentile(arithmetic, 50.0, "m"),
        ).forEach { it.spec.cost(it).assert().describedAs(it.toString()).isEqualTo(OperatorCost.EXPENSIVE) }
    }

    @Test
    fun `a spec rejects a metric of another type`() {
        assertThrows<IllegalArgumentException> { MetricSpec.COUNT.cost(AggregationMetric.Any(field, "m")) }
    }

    private data class Row(
        val input: MetricInput,
        val capabilities: List<QueryCapability>,
        val result: MetricResult,
        val having: Boolean,
        val order: QueryCapability? = null,
    )
}
