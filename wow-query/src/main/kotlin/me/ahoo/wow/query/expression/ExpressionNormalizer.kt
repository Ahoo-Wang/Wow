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

package me.ahoo.wow.query.expression

import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.PortableExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.RelativeTimeExpression

object ExpressionNormalizer {
    fun normalize(expression: QueryExpression): QueryExpression =
        normalize(expression) { it }

    private fun normalize(
        expression: QueryExpression,
        relativeTime: (RelativeTimeExpression) -> QueryExpression
    ): QueryExpression =
        when (expression) {
            is LogicalExpression -> logical(expression.operator, expression.operands, relativeTime)
            is PortableLogicalExpression -> logical(expression.operator, expression.operands, relativeTime)
            is ElementMatchExpression -> ElementMatchExpression(
                expression.field,
                normalize(expression.predicate, relativeTime) as PortableExpression
            )
            is RelativeTimeExpression -> relativeTime(expression)
            else -> expression
        }

    fun logical(operator: LogicalOperator, operands: List<QueryExpression>): QueryExpression {
        return logical(operator, operands) { it }
    }

    private fun logical(
        operator: LogicalOperator,
        operands: List<QueryExpression>,
        relativeTime: (RelativeTimeExpression) -> QueryExpression
    ): QueryExpression {
        val normalized = operands.map { normalize(it, relativeTime) }
        if (operator == LogicalOperator.NOR) {
            return createLogical(operator, normalized)
        }

        val flattened = normalized.flatMap { operand -> flatten(operator, operand) }.toMutableList()
        when (operator) {
            LogicalOperator.AND -> {
                if (flattened.any { it === MatchNone }) {
                    return MatchNone
                }
                flattened.removeAll { it === MatchAll }
            }

            LogicalOperator.OR -> {
                if (flattened.any { it === MatchAll }) {
                    return MatchAll
                }
                flattened.removeAll { it === MatchNone }
            }

            LogicalOperator.NOR -> error("handled above")
        }
        return createLogical(operator, flattened)
    }

    private fun flatten(operator: LogicalOperator, expression: QueryExpression): List<QueryExpression> =
        when {
            expression is LogicalExpression && expression.operator == operator -> expression.operands
            expression is PortableLogicalExpression && expression.operator == operator -> expression.operands
            else -> listOf(expression)
        }

    private fun createLogical(operator: LogicalOperator, operands: List<QueryExpression>): QueryExpression {
        if (operands.isEmpty()) {
            return if (operator == LogicalOperator.AND) MatchAll else MatchNone
        }
        if (operands.size == 1 && operator != LogicalOperator.NOR) {
            return operands.single()
        }
        return if (operands.all { it is PortableExpression }) {
            PortableLogicalExpression(operator, operands.map { it as PortableExpression })
        } else {
            LogicalExpression(operator, operands)
        }
    }
}
