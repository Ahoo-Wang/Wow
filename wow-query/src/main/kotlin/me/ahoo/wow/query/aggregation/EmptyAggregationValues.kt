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

import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.DerivedExpression

/**
 * Empty-bucket semantics shared by Mongo dense fills, Elasticsearch client-side fills and the
 * ungrouped empty summary: counts are zero, value metrics are null, deriveds evaluate in
 * declaration order over the synthetic values (null propagation, divide-by-zero to null).
 */
object EmptyAggregationValues {
    fun values(metrics: List<AggregationMetric>): LinkedHashMap<String, Any?> {
        val values = LinkedHashMap<String, Any?>()
        metrics.forEach { metric ->
            values[metric.alias] = when (metric) {
                is AggregationMetric.Count, is AggregationMetric.DistinctCount -> 0L
                is AggregationMetric.Any, is AggregationMetric.Numeric, is AggregationMetric.Percentile -> null
                is AggregationMetric.Derived -> metric.expression.evaluateOver(values)
            }
        }
        return values
    }

    fun DerivedExpression.evaluateOver(values: Map<String, Any?>): Double? = when (this) {
        is DerivedExpression.MetricRef -> (values[metric] as? Number)?.toDouble()
        is DerivedExpression.Constant -> value
        is DerivedExpression.Binary -> {
            val leftValue = left.evaluateOver(values) ?: return null
            val rightValue = right.evaluateOver(values) ?: return null
            when (operator) {
                AggregationExpressionOperator.ADD -> leftValue + rightValue
                AggregationExpressionOperator.SUBTRACT -> leftValue - rightValue
                AggregationExpressionOperator.MULTIPLY -> leftValue * rightValue
                AggregationExpressionOperator.DIVIDE ->
                    if (rightValue == 0.0) {
                        return null
                    } else {
                        leftValue / rightValue
                    }
            }.takeIf { it.isFinite() }
        }
    }
}
