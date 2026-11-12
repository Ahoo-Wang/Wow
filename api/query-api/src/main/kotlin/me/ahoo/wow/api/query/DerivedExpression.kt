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
 * A derived metric's expression: computed from the group's other metrics, or over the groups as a window.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
@ExperimentalWowApi
sealed interface DerivedExpression {
    class MetricRef @InternalWowApi constructor(val metric: String) : DerivedExpression {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is MetricRef && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "MetricRef(metric=$metric)"

        private fun valueFields(): List<kotlin.Any?> = listOf(metric)

        companion object {
            /** A [MetricRef] of the given values. */
            @JvmStatic
            fun of(metric: String): MetricRef = MetricRef(metric)
        }
    }

    class Constant @InternalWowApi constructor(val value: Double) : DerivedExpression {
        init {
            require(value.isFinite()) { "derived constant must be finite." }
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
        val left: DerivedExpression,
        val right: DerivedExpression,
    ) : DerivedExpression {
        /** This Binary with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            operator: AggregationExpressionOperator = this.operator,
            left: DerivedExpression = this.left,
            right: DerivedExpression = this.right,
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
                left: DerivedExpression,
                right: DerivedExpression,
            ): Binary = Binary(operator, left, right)
        }
    }

    /**
     * A window over the groups of the aggregation: computed from every group the filter selects, after the row-level
     * derived metrics and before HAVING, sort and limit, so neither HAVING nor the limit changes it. [metric] names a
     * non-derived numeric metric declared before the derived metric. A null [metric] value contributes nothing.
     *
     * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
     */
    sealed interface Window : DerivedExpression {
        val metric: String
    }

    /**
     * The sum of [metric] over the groups that share this group's [within] keys, every group when [within] is empty;
     * `null` when every value is `null`. Only COUNT and SUM add up, so only they are accepted.
     */
    class Total @InternalWowApi constructor(
        override val metric: String,
        val within: List<String> = emptyList(),
    ) : Window {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Total && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Total(metric=$metric, within=$within)"

        private fun valueFields(): List<kotlin.Any?> = listOf(metric, within)

        companion object {
            /** A [Total] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(metric: String, within: List<String> = emptyList()): Total = Total(metric, within)
        }
    }

    /**
     * [metric] accumulated along [order] over the groups that share this group's [within] keys: the [function] of
     * every group up to and including this one, or of the last [frame] of them. Groups tied on [order] follow the
     * remaining group keys ascending, in `groupBy` order. `null` when every value in the frame is `null`. SUM accepts
     * only COUNT and SUM; AVG, a moving average with [frame], accepts any numeric metric.
     */
    class Running @InternalWowApi constructor(
        override val metric: String,
        /** A group alias or a non-derived numeric metric alias, with its direction. */
        val order: Sort,
        val within: List<String> = emptyList(),
        val frame: Int? = null,
        val function: RunningFunction = RunningFunction.SUM,
    ) : Window {
        init {
            require(frame == null || frame in 1..MAX_FRAME) { "running frame must be between 1 and $MAX_FRAME." }
        }

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Running && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Running(metric=$metric, order=$order, within=$within, frame=$frame, function=$function)"

        private fun valueFields(): List<kotlin.Any?> = listOf(metric, order, within, frame, function)

        companion object {
            /** A [Running] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                metric: String,
                order: Sort,
                within: List<String> = emptyList(),
                frame: Int? = null,
                function: RunningFunction = RunningFunction.SUM,
            ): Running = Running(metric, order, within, frame, function)
        }
    }

    /**
     * [metric] of the group [shift] steps earlier along the `DATE_HISTOGRAM` group [along], every other group key
     * equal: a step is one bucket of [along], the bucket before in its sequence of buckets (across a daylight-saving
     * transition too, never the bucket itself), or one [period] (such as `YEAR` for the same period last year: the
     * bucket holding the same local time then). The earlier groups are read over the query's own range of [along]'s
     * field, shifted alike. `null` when that group has no records.
     */
    class Previous @InternalWowApi constructor(
        override val metric: String,
        val along: String,
        val shift: Int = 1,
        val period: AggregationDateUnit? = null,
    ) : Window {
        init {
            require(shift in 1..MAX_SHIFT) { "previous shift must be between 1 and $MAX_SHIFT." }
        }

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Previous && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Previous(metric=$metric, along=$along, shift=$shift, period=$period)"

        private fun valueFields(): List<kotlin.Any?> = listOf(metric, along, shift, period)

        companion object {
            /** A [Previous] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                metric: String,
                along: String,
                shift: Int = 1,
                period: AggregationDateUnit? = null,
            ): Previous = Previous(metric, along, shift, period)
        }
    }

    /**
     * The dense rank of [metric] among the groups that share this group's [within] keys, in [direction]: tied values
     * share a rank and ranks have no gaps. `null` for a group whose value is `null`.
     */
    class Rank @InternalWowApi constructor(
        override val metric: String,
        val within: List<String> = emptyList(),
        val direction: Sort.Direction = Sort.Direction.DESC,
    ) : Window {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Rank && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Rank(metric=$metric, within=$within, direction=$direction)"

        private fun valueFields(): List<kotlin.Any?> = listOf(metric, within, direction)

        companion object {
            /** A [Rank] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                metric: String,
                within: List<String> = emptyList(),
                direction: Sort.Direction = Sort.Direction.DESC,
            ): Rank = Rank(metric, within, direction)
        }
    }

    companion object {
        /** The most windows one aggregation may compute. */
        const val MAX_WINDOWS: Int = 8
        const val MAX_FRAME: Int = AggregationQuery.MAX_LIMIT
        const val MAX_SHIFT: Int = 1_000
    }
}

/**
 * The window functions of [DerivedExpression.Window], as a storage declares and the descriptor lists them.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
@ExperimentalWowApi
enum class WindowFunction {
    TOTAL,
    RUNNING,
    PREVIOUS,
    RANK,
}

/**
 * What a [DerivedExpression.Running] window computes over its frame.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
@ExperimentalWowApi
enum class RunningFunction {
    SUM,
    AVG,
}

/** The function of this window. */
@ExperimentalWowApi
val DerivedExpression.Window.windowFunction: WindowFunction
    get() = when (this) {
        is DerivedExpression.Total -> WindowFunction.TOTAL
        is DerivedExpression.Running -> WindowFunction.RUNNING
        is DerivedExpression.Previous -> WindowFunction.PREVIOUS
        is DerivedExpression.Rank -> WindowFunction.RANK
    }
