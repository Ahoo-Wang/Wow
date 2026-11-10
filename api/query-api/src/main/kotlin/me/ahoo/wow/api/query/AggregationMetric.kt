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
sealed interface AggregationMetric {
    val alias: String

    val filter: FilterExpression

    class Count @InternalWowApi constructor(
        override val alias: String,
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }

        /** This Count with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            alias: String = this.alias,
            filter: FilterExpression = this.filter,
        ): Count = Count(alias, filter)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Count && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Count(alias=$alias, filter=$filter)"

        private fun valueFields(): List<kotlin.Any?> = listOf(alias, filter)

        companion object {
            /** A [Count] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(alias: String, filter: FilterExpression = MatchAllFilter): Count = Count(alias, filter)
        }
    }

    class Numeric @InternalWowApi constructor(
        val function: AggregationFunction,
        val expression: AggregationExpression,
        override val alias: String,
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }

        /** This Numeric with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            function: AggregationFunction = this.function,
            expression: AggregationExpression = this.expression,
            alias: String = this.alias,
            filter: FilterExpression = this.filter,
        ): Numeric = Numeric(function, expression, alias, filter)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Numeric && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Numeric(function=$function, expression=$expression, alias=$alias, filter=$filter)"

        private fun valueFields(): List<kotlin.Any?> = listOf(function, expression, alias, filter)

        companion object {
            /** A [Numeric] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                function: AggregationFunction,
                expression: AggregationExpression,
                alias: String,
                filter: FilterExpression = MatchAllFilter,
            ): Numeric = Numeric(function, expression, alias, filter)
        }
    }

    class Any @InternalWowApi constructor(
        val field: QueryField<*>,
        override val alias: String,
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }

        /** This Any with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField<*> = this.field,
            alias: String = this.alias,
            filter: FilterExpression = this.filter,
        ): Any = Any(field, alias, filter)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Any && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Any(field=$field, alias=$alias, filter=$filter)"

        private fun valueFields(): List<kotlin.Any?> = listOf(field, alias, filter)

        companion object {
            /** A [Any] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                field: QueryField<*>,
                alias: String,
                filter: FilterExpression = MatchAllFilter,
            ): Any = Any(field, alias, filter)
        }
    }

    class DistinctCount @InternalWowApi constructor(
        val expression: AggregationExpression,
        override val alias: String,
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }

        /** This DistinctCount with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            expression: AggregationExpression = this.expression,
            alias: String = this.alias,
            filter: FilterExpression = this.filter,
        ): DistinctCount = DistinctCount(expression, alias, filter)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is DistinctCount && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "DistinctCount(expression=$expression, alias=$alias, filter=$filter)"

        private fun valueFields(): List<kotlin.Any?> = listOf(expression, alias, filter)

        companion object {
            /** A [DistinctCount] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                expression: AggregationExpression,
                alias: String,
                filter: FilterExpression = MatchAllFilter,
            ): DistinctCount = DistinctCount(expression, alias, filter)
        }
    }

    class Percentile @InternalWowApi constructor(
        val expression: AggregationExpression,
        val percentile: Double,
        override val alias: String,
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
            require(percentile.isFinite() && percentile > 0.0 && percentile < 100.0) {
                "percentile must be finite and within (0, 100)."
            }
        }

        /** This Percentile with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            expression: AggregationExpression = this.expression,
            percentile: Double = this.percentile,
            alias: String = this.alias,
            filter: FilterExpression = this.filter,
        ): Percentile = Percentile(expression, percentile, alias, filter)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Percentile && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Percentile(expression=$expression, percentile=$percentile, alias=$alias, filter=$filter)"

        private fun valueFields(): List<kotlin.Any?> = listOf(expression, percentile, alias, filter)

        companion object {
            /** A [Percentile] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                expression: AggregationExpression,
                percentile: Double,
                alias: String,
                filter: FilterExpression = MatchAllFilter,
            ): Percentile = Percentile(expression, percentile, alias, filter)
        }
    }

    class Derived @InternalWowApi constructor(
        override val alias: String,
        val expression: DerivedExpression,
    ) : AggregationMetric {
        override val filter: FilterExpression get() = MatchAllFilter

        init {
            requireAggregationAlias(alias)
        }

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Derived && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Derived(alias=$alias, expression=$expression)"

        private fun valueFields(): List<kotlin.Any?> = listOf(alias, expression)

        companion object {
            /** A [Derived] of the given values. */
            @JvmStatic
            fun of(alias: String, expression: DerivedExpression): Derived = Derived(alias, expression)
        }
    }

    /**
     * The value of [field] on the earliest ([First]) or latest ([Last]) record of the group by [orderBy], among the
     * records that have both a value and an [orderBy] position; `null` when none has. [orderBy] defaults to the
     * model's event time. Records tied on [orderBy] may yield any one of their values.
     */
    sealed interface Edge : AggregationMetric {
        val field: QueryField<*>

        /** The ordering field; `null` for the model's event time. */
        val orderBy: QueryField<*>?
    }

    class First @InternalWowApi constructor(
        override val field: QueryField<*>,
        override val alias: String,
        override val orderBy: QueryField<*>? = null,
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric, Edge {
        init {
            requireAggregationAlias(alias)
        }

        /** This First with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField<*> = this.field,
            alias: String = this.alias,
            orderBy: QueryField<*>? = this.orderBy,
            filter: FilterExpression = this.filter,
        ): First = First(field, alias, orderBy, filter)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is First && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "First(field=$field, alias=$alias, orderBy=$orderBy, filter=$filter)"

        private fun valueFields(): List<kotlin.Any?> = listOf(field, alias, orderBy, filter)

        companion object {
            /** A [First] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                field: QueryField<*>,
                alias: String,
                orderBy: QueryField<*>? = null,
                filter: FilterExpression = MatchAllFilter,
            ): First = First(field, alias, orderBy, filter)
        }
    }

    class Last @InternalWowApi constructor(
        override val field: QueryField<*>,
        override val alias: String,
        override val orderBy: QueryField<*>? = null,
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric, Edge {
        init {
            requireAggregationAlias(alias)
        }

        /** This Last with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField<*> = this.field,
            alias: String = this.alias,
            orderBy: QueryField<*>? = this.orderBy,
            filter: FilterExpression = this.filter,
        ): Last = Last(field, alias, orderBy, filter)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Last && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Last(field=$field, alias=$alias, orderBy=$orderBy, filter=$filter)"

        private fun valueFields(): List<kotlin.Any?> = listOf(field, alias, orderBy, filter)

        companion object {
            /** A [Last] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                field: QueryField<*>,
                alias: String,
                orderBy: QueryField<*>? = null,
                filter: FilterExpression = MatchAllFilter,
            ): Last = Last(field, alias, orderBy, filter)
        }
    }
}

@ExperimentalWowApi
enum class AggregationFunction {
    SUM,
    AVG,
    MIN,
    MAX,
    STDDEV,
    VARIANCE,
}
