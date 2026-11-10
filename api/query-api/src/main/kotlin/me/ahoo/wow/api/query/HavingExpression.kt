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

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi

@ExperimentalWowApi
sealed interface HavingExpression {
    class Condition @InternalWowApi constructor(
        val metric: String,
        val operator: ComparisonOperator,
        val value: Double
    ) : HavingExpression {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Condition && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Condition(metric=$metric, operator=$operator, value=$value)"

        private fun valueFields(): List<kotlin.Any?> = listOf(metric, operator, value)

        companion object {
            /** A [Condition] of the given values. */
            @JvmStatic
            fun of(
                metric: String,
                operator: ComparisonOperator,
                value: Double,
            ): Condition = Condition(metric, operator, value)
        }
    }

    class Between @InternalWowApi constructor(val metric: String, val lower: Double, val upper: Double) : HavingExpression {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Between && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Between(metric=$metric, lower=$lower, upper=$upper)"

        private fun valueFields(): List<kotlin.Any?> = listOf(metric, lower, upper)

        companion object {
            /** A [Between] of the given values. */
            @JvmStatic
            fun of(metric: String, lower: Double, upper: Double): Between = Between(metric, lower, upper)
        }
    }

    class In @InternalWowApi constructor(
        val metric: String,
        val values: List<Double>,
    ) : HavingExpression {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is In && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "In(metric=$metric, values=$values)"

        private fun valueFields(): List<kotlin.Any?> = listOf(metric, values)

        companion object {
            /** A [In] of the given values. */
            @JvmStatic
            fun of(metric: String, values: List<Double>): In = In(metric, values)
        }
    }

    class IsNull @InternalWowApi constructor(val metric: String, val negated: Boolean = false) : HavingExpression {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is IsNull && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "IsNull(metric=$metric, negated=$negated)"

        private fun valueFields(): List<kotlin.Any?> = listOf(metric, negated)

        companion object {
            /** A [IsNull] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(metric: String, negated: Boolean = false): IsNull = IsNull(metric, negated)
        }
    }

    class And @InternalWowApi constructor(
        val operands: List<HavingExpression>,
    ) : HavingExpression {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is And && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "And(operands=$operands)"

        private fun valueFields(): List<kotlin.Any?> = listOf(operands)

        companion object {
            /** A [And] of the given values. */
            @JvmStatic
            fun of(operands: List<HavingExpression>): And = And(operands)
        }
    }

    class Or @InternalWowApi constructor(
        val operands: List<HavingExpression>,
    ) : HavingExpression {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Or && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Or(operands=$operands)"

        private fun valueFields(): List<kotlin.Any?> = listOf(operands)

        companion object {
            /** A [Or] of the given values. */
            @JvmStatic
            fun of(operands: List<HavingExpression>): Or = Or(operands)
        }
    }
}
