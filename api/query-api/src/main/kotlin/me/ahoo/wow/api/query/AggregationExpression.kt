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

/**
 * One numeric contribution per current root or expanded record, for data conforming to its numeric schema.
 * FIELD selects the sole non-null numeric value;
 * missing, empty and multi-valued fields do not contribute. Repeated numeric values remain separate values.
 * BINARY computes finite doubles; it does not pair arrays or preserve arbitrary native numeric precision.
 */
@ExperimentalWowApi
sealed interface AggregationExpression {
    class Field @InternalWowApi constructor(val field: QueryField<*>) : AggregationExpression {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Field && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Field(field=$field)"

        private fun valueFields(): List<kotlin.Any?> = listOf(field)

        companion object {
            /** A [Field] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): Field = Field(field)
        }
    }

    class Constant @InternalWowApi constructor(val value: Double) : AggregationExpression {
        init {
            require(value.isFinite()) { "aggregation constant must be finite." }
        }

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Constant && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Constant(value=$value)"

        private fun valueFields(): List<kotlin.Any?> = listOf(value)

        companion object {
            /** A [Constant] of the given values. */
            @JvmStatic
            fun of(value: Double): Constant = Constant(value)
        }
    }

    class Binary @InternalWowApi constructor(
        val operator: AggregationExpressionOperator,
        val left: AggregationExpression,
        val right: AggregationExpression,
    ) : AggregationExpression {
        /** This Binary with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            operator: AggregationExpressionOperator = this.operator,
            left: AggregationExpression = this.left,
            right: AggregationExpression = this.right,
        ): Binary = Binary(operator, left, right)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Binary && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Binary(operator=$operator, left=$left, right=$right)"

        private fun valueFields(): List<kotlin.Any?> = listOf(operator, left, right)

        companion object {
            /** A [Binary] of the given values. */
            @JvmStatic
            fun of(
                operator: AggregationExpressionOperator,
                left: AggregationExpression,
                right: AggregationExpression,
            ): Binary = Binary(operator, left, right)
        }
    }

    /**
     * The elapsed time from the instant in [from] to the instant in [to], `to − from`, in [unit] as a decimal: negative
     * when [to] is earlier, and no contribution when either instant is absent. Both fields must be single-valued
     * temporal fields; each is read in its own temporal encoding.
     */
    class DateDiff @InternalWowApi constructor(
        val from: QueryField<*>,
        val to: QueryField<*>,
        val unit: DateDiffUnit,
    ) : AggregationExpression {
        /** This DateDiff with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            from: QueryField<*> = this.from,
            to: QueryField<*> = this.to,
            unit: DateDiffUnit = this.unit,
        ): DateDiff = DateDiff(from, to, unit)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is DateDiff && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "DateDiff(from=$from, to=$to, unit=$unit)"

        private fun valueFields(): List<kotlin.Any?> = listOf(from, to, unit)

        companion object {
            /** A [DateDiff] of the given values. */
            @JvmStatic
            fun of(from: QueryField<*>, to: QueryField<*>, unit: DateDiffUnit): DateDiff = DateDiff(from, to, unit)
        }
    }
}

/**
 * A fixed-length unit of elapsed time. Calendar units (months, years) have no fixed length and depend on a time zone,
 * so they are not offered; a `DAY` is exactly 24 hours.
 */
@ExperimentalWowApi
enum class DateDiffUnit(val millis: Long) {
    SECOND(1_000L),
    MINUTE(60_000L),
    HOUR(3_600_000L),
    DAY(86_400_000L),
}

/** The fields an expression reads, in tree order. */
@ExperimentalWowApi
val AggregationExpression.fields: List<QueryField<*>>
    get() = when (this) {
        is AggregationExpression.Field -> listOf(field)
        is AggregationExpression.Constant -> emptyList()
        is AggregationExpression.Binary -> left.fields + right.fields
        is AggregationExpression.DateDiff -> listOf(from, to)
    }

@ExperimentalWowApi
enum class AggregationExpressionOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
}
