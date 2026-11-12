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
 * A dimension an aggregation groups its records by: a field's terms, a numeric histogram, a date histogram or a
 * date part.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
@ExperimentalWowApi
sealed interface AggregationGroup {
    /** The grouped field; `null` only for a TERMS or HISTOGRAM group whose input is an expression. */
    val field: QueryField<*>?
    val alias: String

    /**
     * Where [field] is read when the query expands elements: `null` (the innermost element, relative to its container)
     * or [AggregationGroupScope.ROOT] (a root field, by its absolute path), so a count of elements can be grouped by a
     * field of the record they belong to, such as its creation day. Without elements both read the root.
     */
    val scope: AggregationGroupScope?

    /** Groups by the value of [field], or of [expression] when the group names one instead. */
    class Terms @InternalWowApi constructor(
        override val field: QueryField<*>? = null,
        override val alias: String,
        val missingKey: String? = null,
        val expression: AggregationExpression? = null,
        override val scope: AggregationGroupScope? = null,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            requireGroupInput(field, expression, "TERMS")
            requireGroupScope(scope, expression, "TERMS")
            if (missingKey != null) {
                require(missingKey.isNotBlank()) { "terms missingKey must not be blank." }
                require(expression == null) { "terms missingKey requires a field input." }
            }
        }

        /** This Terms with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField<*>? = this.field,
            alias: String = this.alias,
            missingKey: String? = this.missingKey,
            expression: AggregationExpression? = this.expression,
            scope: AggregationGroupScope? = this.scope,
        ): Terms = Terms(field, alias, missingKey, expression, scope)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Terms && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Terms(field=$field, alias=$alias, missingKey=$missingKey, expression=$expression, scope=$scope)"

        private fun valueFields(): List<kotlin.Any?> = listOf(field, alias, missingKey, expression, scope)

        companion object {
            /** A [Terms] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                field: QueryField<*>? = null,
                alias: String,
                missingKey: String? = null,
                expression: AggregationExpression? = null,
                scope: AggregationGroupScope? = null,
            ): Terms = Terms(field, alias, missingKey, expression, scope)
        }
    }

    /** Buckets the numeric value of [field], or of [expression] when the group names one instead. */
    class Histogram @InternalWowApi constructor(
        override val field: QueryField<*>? = null,
        override val alias: String,
        val interval: Double,
        val expression: AggregationExpression? = null,
        override val scope: AggregationGroupScope? = null,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            requireGroupInput(field, expression, "HISTOGRAM")
            requireGroupScope(scope, expression, "HISTOGRAM")
            require(interval.isFinite() && interval > 0.0) {
                "histogram interval must be finite and greater than 0."
            }
        }

        /** This Histogram with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField<*>? = this.field,
            alias: String = this.alias,
            interval: Double = this.interval,
            expression: AggregationExpression? = this.expression,
            scope: AggregationGroupScope? = this.scope,
        ): Histogram = Histogram(field, alias, interval, expression, scope)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Histogram && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Histogram(field=$field, alias=$alias, interval=$interval, expression=$expression, scope=$scope)"

        private fun valueFields(): List<kotlin.Any?> = listOf(field, alias, interval, expression, scope)

        companion object {
            /** A [Histogram] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                field: QueryField<*>? = null,
                alias: String,
                interval: Double,
                expression: AggregationExpression? = null,
                scope: AggregationGroupScope? = null,
            ): Histogram = Histogram(field, alias, interval, expression, scope)
        }
    }

    /**
     * Buckets the instant of [field] by [unit] in [timeZone]: a bucket's key is the epoch milliseconds of the instant
     * at which the local [unit] period containing the record begins, in that period's own UTC offset (a week begins
     * on Monday; a day, week, month, quarter or year at its first local midnight, or where a transition puts it).
     * So a bucket never starts after its records, the hour a daylight-saving fall-back repeats is two buckets, and the
     * hour a spring-forward skips is none.
     */
    class DateHistogram @InternalWowApi constructor(
        override val field: QueryField<*>,
        override val alias: String,
        val unit: AggregationDateUnit,
        /**
         * The time zone, an IANA region such as `Asia/Shanghai` or an offset such as `+08:00`; `null`, the default, is
         * UTC, never the server's own zone.
         */
        val timeZone: String? = null,
        val dense: Boolean = false,
        override val scope: AggregationGroupScope? = null,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            timeZone?.let(::zoneIdOf)
        }

        /**
         * The zone this group reads its instants in, as written, the text a backend writes: [timeZone], or `UTC` when
         * it names none. A relative-time filter's [RelativeTimeFilter.resolvedTimeZone] is the same default, parsed.
         */
        fun effectiveTimeZone(): String = timeZone ?: UTC

        /** This DateHistogram with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField<*> = this.field,
            alias: String = this.alias,
            unit: AggregationDateUnit = this.unit,
            timeZone: String? = this.timeZone,
            dense: Boolean = this.dense,
            scope: AggregationGroupScope? = this.scope,
        ): DateHistogram = DateHistogram(field, alias, unit, timeZone, dense, scope)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is DateHistogram && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "DateHistogram(field=$field, alias=$alias, unit=$unit, timeZone=$timeZone, dense=$dense, scope=$scope)"

        private fun valueFields(): List<kotlin.Any?> = listOf(field, alias, unit, timeZone, dense, scope)

        companion object {
            /** A [DateHistogram] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                field: QueryField<*>,
                alias: String,
                unit: AggregationDateUnit,
                timeZone: String? = null,
                dense: Boolean = false,
                scope: AggregationGroupScope? = null,
            ): DateHistogram = DateHistogram(field, alias, unit, timeZone, dense, scope)
        }
    }

    /**
     * Groups by one calendar part of the field's instant in [timeZone], such as the weekday or the hour, so records
     * from different days fall into one bucket. Keys are integers from [AggregationDatePart]'s fixed domain; [dense]
     * fills every key of that domain that has no records with the empty value of each metric.
     */
    class DatePart @InternalWowApi constructor(
        override val field: QueryField<*>,
        override val alias: String,
        val part: AggregationDatePart,
        /**
         * The time zone, an IANA region such as `Asia/Shanghai` or an offset such as `+08:00`; `null`, the default, is
         * UTC, never the server's own zone.
         */
        val timeZone: String? = null,
        val dense: Boolean = false,
        override val scope: AggregationGroupScope? = null,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            timeZone?.let(::zoneIdOf)
        }

        /**
         * The zone this group reads its instants in, as written, the text a backend writes: [timeZone], or `UTC` when
         * it names none. A relative-time filter's [RelativeTimeFilter.resolvedTimeZone] is the same default, parsed.
         */
        fun effectiveTimeZone(): String = timeZone ?: UTC

        /** This DatePart with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField<*> = this.field,
            alias: String = this.alias,
            part: AggregationDatePart = this.part,
            timeZone: String? = this.timeZone,
            dense: Boolean = this.dense,
            scope: AggregationGroupScope? = this.scope,
        ): DatePart = DatePart(field, alias, part, timeZone, dense, scope)

        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is DatePart && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "DatePart(field=$field, alias=$alias, part=$part, timeZone=$timeZone, dense=$dense, scope=$scope)"

        private fun valueFields(): List<kotlin.Any?> = listOf(field, alias, part, timeZone, dense, scope)

        companion object {
            /** A [DatePart] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                field: QueryField<*>,
                alias: String,
                part: AggregationDatePart,
                timeZone: String? = null,
                dense: Boolean = false,
                scope: AggregationGroupScope? = null,
            ): DatePart = DatePart(field, alias, part, timeZone, dense, scope)
        }
    }
}

/** The computed input of a TERMS or HISTOGRAM group in place of its field, or `null`. */
@ExperimentalWowApi
val AggregationGroup.inputExpression: AggregationExpression?
    get() = when (this) {
        is AggregationGroup.Terms -> expression
        is AggregationGroup.Histogram -> expression
        is AggregationGroup.DateHistogram, is AggregationGroup.DatePart -> null
    }

/**
 * A calendar part of an instant, with its fixed integer domain `[min, max]`.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
@ExperimentalWowApi
enum class AggregationDatePart(val min: Int, val max: Int) {
    /** ISO weekday: 1 is Monday, 7 is Sunday. */
    DAY_OF_WEEK(1, 7),

    /** The day of the month, 1 to 31; days 29 to 31 exist only in the months that have them. */
    DAY_OF_MONTH(1, 31),

    /** The hour of the day on the local wall clock, 0 to 23. */
    HOUR_OF_DAY(0, 23),

    /** The month of the year, 1 (January) to 12. */
    MONTH_OF_YEAR(1, 12),
    ;

    /** Every key of the domain, ascending. */
    val domain: IntRange
        get() = min..max
}

/**
 * The calendar unit a date histogram buckets by.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
@ExperimentalWowApi
enum class AggregationDateUnit {
    YEAR,
    QUARTER,
    MONTH,
    WEEK,
    DAY,
    HOUR,
    MINUTE,
    SECOND,
}

/**
 * Where a group reads its field when the query expands elements; see [AggregationGroup.scope].
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 */
@ExperimentalWowApi
enum class AggregationGroupScope {
    /** The innermost element: the field is relative to its container. The meaning of an absent scope. */
    ELEMENT,

    /** The root record: the field is its absolute path. */
    ROOT,
}

/** An expression's fields are relative to the innermost element, so a ROOT group names a field instead. */
private fun requireGroupScope(scope: AggregationGroupScope?, expression: AggregationExpression?, type: String) {
    require(scope != AggregationGroupScope.ROOT || expression == null) {
        "$type with a ROOT scope requires a field input."
    }
}

private fun requireGroupInput(field: QueryField<*>?, expression: AggregationExpression?, type: String) {
    require((field == null) != (expression == null)) { "$type group requires exactly one of field and expression." }
    expression?.let { listOf(it).requireValidExpressionTrees() }
}
