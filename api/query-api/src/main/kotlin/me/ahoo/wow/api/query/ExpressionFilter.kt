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

package me.ahoo.wow.api.query

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * Compares a computed [expression], for example the hours from `paidAt` to `shippedAt`, with [value]. A record whose
 * expression has no value (an operand is absent, or a division by zero) does not match, whatever the [comparison].
 * The expression is evaluated per record and cannot use an index, so the operator is expensive.
 */
class ExpressionFilter @InternalWowApi constructor(
    val expression: AggregationExpression,
    val comparison: ComparisonOperator,
    val value: Double,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.EXPRESSION

    init {
        require(value.isFinite()) { "EXPRESSION value must be finite." }
        require(expression.fields.isNotEmpty()) { "EXPRESSION must read at least one field." }
        listOf(expression).requireValidExpressionTrees()
    }

    /** This ExpressionFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        expression: AggregationExpression = this.expression,
        comparison: ComparisonOperator = this.comparison,
        value: Double = this.value,
    ): ExpressionFilter = ExpressionFilter(expression, comparison, value)

    override fun equals(other: Any?): Boolean =
        this === other || other is ExpressionFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ExpressionFilter(expression=$expression, comparison=$comparison, value=$value)"

    private fun valueFields(): List<Any?> = listOf(expression, comparison, value)

    companion object {
        /** A [ExpressionFilter] of the given values. */
        @JvmStatic
        fun of(
            expression: AggregationExpression,
            comparison: ComparisonOperator,
            value: Double,
        ): ExpressionFilter = ExpressionFilter(expression, comparison, value)
    }
}
