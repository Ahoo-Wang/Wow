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
class AggregationQuery @InternalWowApi constructor(
    override val filter: FilterExpression = MatchAllFilter,
    val elements: List<AggregationElement> = emptyList(),
    val groupBy: List<AggregationGroup> = emptyList(),
    val metrics: List<AggregationMetric>,
    override val sort: List<Sort> = emptyList(),
    val limit: Int = DEFAULT_LIMIT,
    val having: HavingExpression? = null,
) : FilterCapable<AggregationQuery>, SortCapable {
    init {
        require(elements.size <= MAX_ELEMENTS) { "elements must contain at most $MAX_ELEMENTS paths." }
        require(groupBy.size <= MAX_GROUPS) { "groupBy must contain at most $MAX_GROUPS dimensions." }
        require(metrics.isNotEmpty()) { "metrics must not be empty." }
        require(metrics.size <= MAX_METRICS) { "metrics must contain at most $MAX_METRICS entries." }
        require(sort.size <= Sort.MAX_FIELDS) { "sort must contain at most ${Sort.MAX_FIELDS} fields." }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT." }
        require(groupBy.isNotEmpty() || sort.isEmpty()) { "sort requires at least one groupBy." }
        metrics.requireValidExpressions()
        metrics.requireValidDerivedMetrics(groupBy)
        requireValidHaving(having, groupBy, metrics)
        groupBy.forEach { group ->
            if (group is AggregationGroup.DateHistogram && group.dense) {
                require(groupBy.size == 1) { "dense requires DATE_HISTOGRAM to be the only groupBy." }
            }
            if (group is AggregationGroup.DatePart && group.dense) {
                require(groupBy.size == 1) { "dense requires DATE_PART to be the only groupBy." }
            }
        }

        val aliases = groupBy.map(AggregationGroup::alias) + metrics.map(AggregationMetric::alias)
        require(aliases.distinct().size == aliases.size) { "aggregation aliases must be unique." }
        val sortFields = sort.map { it.field.path }
        require(sortFields.distinct().size == sortFields.size) { "sort fields must be unique." }
        require(sortFields.all(aliases::contains)) { "sort fields must reference aggregation aliases." }
        require(effectiveSort().size <= Sort.MAX_FIELDS) {
            "effective sort must contain at most ${Sort.MAX_FIELDS} fields."
        }
        requireRootGroupsFirst()
    }

    /**
     * With [elements], the groups of a ROOT scope lead the effective sort: a storage reads the root records' keys
     * before the keys of their elements, so a root key nested under an element key has no native order.
     */
    private fun requireRootGroupsFirst() {
        if (elements.isEmpty()) return
        val root = groupBy.associate { it.alias to (it.scope == AggregationGroupScope.ROOT) }
        val order = effectiveSort().mapNotNull { root[it.field.path] }
        require(order.dropWhile { it }.none { it }) {
            "groups with a ROOT scope must sort before the element groups."
        }
    }

    override fun withFilter(newFilter: FilterExpression): AggregationQuery = AggregationQuery(
        newFilter,
        elements,
        groupBy,
        metrics,
        sort,
        limit,
        having
    )

    fun effectiveSort(): List<Sort> = buildList {
        addAll(sort)
        val sorted = sort.mapTo(hashSetOf()) { it.field.path }
        groupBy.map(AggregationGroup::alias)
            .filterNot(sorted::contains)
            .forEach { add(Sort(QueryField(it), Sort.Direction.ASC)) }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 10_000
        const val MAX_ELEMENTS: Int = 5
        const val MAX_GROUPS: Int = 32
        const val MAX_METRICS: Int = 64

        const val MAX_EXPRESSION_DEPTH: Int = 8
        const val MAX_EXPRESSION_NODES: Int = 256

        /** A [AggregationQuery] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            filter: FilterExpression = MatchAllFilter,
            elements: List<AggregationElement> = emptyList(),
            groupBy: List<AggregationGroup> = emptyList(),
            metrics: List<AggregationMetric>,
            sort: List<Sort> = emptyList(),
            limit: Int = DEFAULT_LIMIT,
            having: HavingExpression? = null,
        ): AggregationQuery = AggregationQuery(filter, elements, groupBy, metrics, sort, limit, having)
    }

    /** This AggregationQuery with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        filter: FilterExpression = this.filter,
        elements: List<AggregationElement> = this.elements,
        groupBy: List<AggregationGroup> = this.groupBy,
        metrics: List<AggregationMetric> = this.metrics,
        sort: List<Sort> = this.sort,
        limit: Int = this.limit,
        having: HavingExpression? = this.having,
    ): AggregationQuery = AggregationQuery(filter, elements, groupBy, metrics, sort, limit, having)

    override fun equals(other: kotlin.Any?): Boolean =
        this === other || other is AggregationQuery && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "AggregationQuery(filter=$filter, elements=$elements, groupBy=$groupBy, metrics=$metrics, sort=$sort, limit=$limit, having=$having)"

    private fun valueFields(): List<kotlin.Any?> = listOf(filter, elements, groupBy, metrics, sort, limit, having)
}

@ExperimentalWowApi
class AggregationElement @InternalWowApi constructor(
    val path: QueryField,
    val filter: FilterExpression = MatchAllFilter,
) {
    init {
        require(filter.containsElementUnsupportedFilter(fieldSearch = false).not()) {
            "Aggregation element filter cannot contain root filters."
        }
    }

    override fun equals(other: kotlin.Any?): Boolean =
        this === other || other is AggregationElement && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "AggregationElement(path=$path, filter=$filter)"

    private fun valueFields(): List<kotlin.Any?> = listOf(path, filter)

    companion object {
        /** A [AggregationElement] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            path: QueryField,
            filter: FilterExpression = MatchAllFilter,
        ): AggregationElement = AggregationElement(path, filter)
    }
}

@ExperimentalWowApi
sealed interface AggregationGroup {
    /** The grouped field; `null` only for a TERMS or HISTOGRAM group whose input is an expression. */
    val field: QueryField?
    val alias: String

    /**
     * Where [field] is read when the query expands elements: `null` (the innermost element, relative to its container)
     * or [AggregationGroupScope.ROOT] (a root field, by its absolute path), so a count of elements can be grouped by a
     * field of the record they belong to, such as its creation day. Without elements both read the root.
     */
    val scope: AggregationGroupScope?

    /** Groups by the value of [field], or of [expression] when the group names one instead. */
    class Terms @InternalWowApi constructor(
        override val field: QueryField? = null,
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
            field: QueryField? = this.field,
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
                field: QueryField? = null,
                alias: String,
                missingKey: String? = null,
                expression: AggregationExpression? = null,
                scope: AggregationGroupScope? = null,
            ): Terms = Terms(field, alias, missingKey, expression, scope)
        }
    }

    /** Buckets the numeric value of [field], or of [expression] when the group names one instead. */
    class Histogram @InternalWowApi constructor(
        override val field: QueryField? = null,
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
            field: QueryField? = this.field,
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
                field: QueryField? = null,
                alias: String,
                interval: Double,
                expression: AggregationExpression? = null,
                scope: AggregationGroupScope? = null,
            ): Histogram = Histogram(field, alias, interval, expression, scope)
        }
    }

    class DateHistogram @InternalWowApi constructor(
        override val field: QueryField,
        override val alias: String,
        val unit: AggregationDateUnit,
        val timeZone: String = "UTC",
        val dense: Boolean = false,
        override val scope: AggregationGroupScope? = null,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            zoneIdOf(timeZone)
        }

        /** This DateHistogram with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField = this.field,
            alias: String = this.alias,
            unit: AggregationDateUnit = this.unit,
            timeZone: String = this.timeZone,
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
                field: QueryField,
                alias: String,
                unit: AggregationDateUnit,
                timeZone: String = "UTC",
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
        override val field: QueryField,
        override val alias: String,
        val part: AggregationDatePart,
        val timeZone: String = "UTC",
        val dense: Boolean = false,
        override val scope: AggregationGroupScope? = null,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            zoneIdOf(timeZone)
        }

        /** This DatePart with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField = this.field,
            alias: String = this.alias,
            part: AggregationDatePart = this.part,
            timeZone: String = this.timeZone,
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
                field: QueryField,
                alias: String,
                part: AggregationDatePart,
                timeZone: String = "UTC",
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

/** A calendar part of an instant, with its fixed integer domain `[min, max]`. */
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
 * One numeric contribution per current root or expanded record, for data conforming to its numeric schema.
 * FIELD selects the sole non-null numeric value;
 * missing, empty and multi-valued fields do not contribute. Repeated numeric values remain separate values.
 * BINARY computes finite doubles; it does not pair arrays or preserve arbitrary native numeric precision.
 */
@ExperimentalWowApi
sealed interface AggregationExpression {
    class Field @InternalWowApi constructor(val field: QueryField) : AggregationExpression {
        override fun equals(other: kotlin.Any?): Boolean =
            this === other || other is Field && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Field(field=$field)"

        private fun valueFields(): List<kotlin.Any?> = listOf(field)

        companion object {
            /** A [Field] of the given values. */
            @JvmStatic
            fun of(field: QueryField): Field = Field(field)
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
        val from: QueryField,
        val to: QueryField,
        val unit: DateDiffUnit,
    ) : AggregationExpression {
        /** This DateDiff with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            from: QueryField = this.from,
            to: QueryField = this.to,
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
            fun of(from: QueryField, to: QueryField, unit: DateDiffUnit): DateDiff = DateDiff(from, to, unit)
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
val AggregationExpression.fields: List<QueryField>
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
        val field: QueryField,
        override val alias: String,
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }

        /** This Any with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField = this.field,
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
                field: QueryField,
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
        val field: QueryField

        /** The ordering field; `null` for the model's event time. */
        val orderBy: QueryField?
    }

    class First @InternalWowApi constructor(
        override val field: QueryField,
        override val alias: String,
        override val orderBy: QueryField? = null,
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric, Edge {
        init {
            requireAggregationAlias(alias)
        }

        /** This First with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField = this.field,
            alias: String = this.alias,
            orderBy: QueryField? = this.orderBy,
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
                field: QueryField,
                alias: String,
                orderBy: QueryField? = null,
                filter: FilterExpression = MatchAllFilter,
            ): First = First(field, alias, orderBy, filter)
        }
    }

    class Last @InternalWowApi constructor(
        override val field: QueryField,
        override val alias: String,
        override val orderBy: QueryField? = null,
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric, Edge {
        init {
            requireAggregationAlias(alias)
        }

        /** This Last with the given values replaced; for Wow's own query rewriting. */
        @InternalWowApi
        fun copy(
            field: QueryField = this.field,
            alias: String = this.alias,
            orderBy: QueryField? = this.orderBy,
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
                field: QueryField,
                alias: String,
                orderBy: QueryField? = null,
                filter: FilterExpression = MatchAllFilter,
            ): Last = Last(field, alias, orderBy, filter)
        }
    }
}

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
     * equal: a step is one bucket of [along], or one [period] (such as `YEAR` for the same period last year). The
     * earlier groups are read over the query's own range of [along]'s field, shifted. `null` when that group has no
     * records.
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

/** The window functions of [DerivedExpression.Window], as a storage declares and the descriptor lists them. */
@ExperimentalWowApi
enum class WindowFunction {
    TOTAL,
    RUNNING,
    PREVIOUS,
    RANK,
}

/** What a [DerivedExpression.Running] window computes over its frame. */
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

@ExperimentalWowApi
enum class ComparisonOperator {
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE,
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

/** Where a group reads its field when the query expands elements; see [AggregationGroup.scope]. */
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

private fun requireGroupInput(field: QueryField?, expression: AggregationExpression?, type: String) {
    require((field == null) != (expression == null)) { "$type group requires exactly one of field and expression." }
    expression?.let { listOf(it).requireValidExpressionTrees() }
}

private fun requireAggregationAlias(alias: String) {
    require('.' !in alias) { "aggregation alias must contain one segment." }
    require(!alias.startsWith("__wow")) { "aggregation alias must not use the reserved __wow prefix." }
    QueryField(alias)
}

private data class PendingExpression(
    val expression: AggregationExpression,
    val depth: Int,
)

private fun List<AggregationMetric>.requireValidExpressions() {
    mapNotNull { metric ->
        when (metric) {
            is AggregationMetric.Numeric -> metric.expression
            is AggregationMetric.DistinctCount -> metric.expression
            is AggregationMetric.Percentile -> metric.expression
            is AggregationMetric.Count, is AggregationMetric.Any, is AggregationMetric.Derived,
            is AggregationMetric.Edge,
            -> null
        }
    }.requireValidExpressionTrees()
}

/** Bounds the depth of each tree and the nodes of all of them together. */
internal fun List<AggregationExpression>.requireValidExpressionTrees() {
    val pending = ArrayDeque<PendingExpression>()
    forEach { pending.addLast(PendingExpression(it, 1)) }
    var nodes = 0
    while (pending.isNotEmpty()) {
        val (expression, depth) = pending.removeLast()
        require(depth <= AggregationQuery.MAX_EXPRESSION_DEPTH) {
            "aggregation expression depth must be at most ${AggregationQuery.MAX_EXPRESSION_DEPTH}."
        }
        nodes++
        require(nodes <= AggregationQuery.MAX_EXPRESSION_NODES) {
            "aggregation expressions must contain at most ${AggregationQuery.MAX_EXPRESSION_NODES} nodes."
        }
        when (expression) {
            is AggregationExpression.Field,
            is AggregationExpression.Constant,
            is AggregationExpression.DateDiff,
            -> Unit

            is AggregationExpression.Binary -> {
                pending.addLast(PendingExpression(expression.left, depth + 1))
                pending.addLast(PendingExpression(expression.right, depth + 1))
            }
        }
    }
}

private data class PendingDerivedExpression(
    val expression: DerivedExpression,
    val depth: Int,
)

private fun List<AggregationMetric>.requireValidDerivedMetrics(groupBy: List<AggregationGroup>) {
    val declared = LinkedHashMap<String, AggregationMetric>()
    val counter = DerivedCounter()
    forEach { metric ->
        if (metric is AggregationMetric.Derived) {
            metric.requireValidDerivedExpression(declared, groupBy, counter)
        }
        declared[metric.alias] = metric
    }
}

/** The derived nodes and windows of a query, counted across its derived metrics. */
private class DerivedCounter {
    var nodes = 0
    var windows = 0
}

/** The non-numeric type of [metric], or `null` when its value is a number. */
internal fun nonNumericType(metric: AggregationMetric): String? = when (metric) {
    is AggregationMetric.Any -> "ANY"
    is AggregationMetric.First -> "FIRST"
    is AggregationMetric.Last -> "LAST"
    else -> null
}

/** [declared] holds the metrics declared before this one, by alias. */
private fun AggregationMetric.Derived.requireValidDerivedExpression(
    declared: Map<String, AggregationMetric>,
    groupBy: List<AggregationGroup>,
    counter: DerivedCounter,
) {
    val pending = ArrayDeque<PendingDerivedExpression>()
    pending.addLast(PendingDerivedExpression(expression, 1))
    while (pending.isNotEmpty()) {
        val (current, depth) = pending.removeLast()
        require(depth <= AggregationQuery.MAX_EXPRESSION_DEPTH) {
            "derived expression depth must be at most ${AggregationQuery.MAX_EXPRESSION_DEPTH}."
        }
        counter.nodes++
        require(counter.nodes <= AggregationQuery.MAX_EXPRESSION_NODES) {
            "derived expressions must contain at most ${AggregationQuery.MAX_EXPRESSION_NODES} nodes."
        }
        when (current) {
            is DerivedExpression.MetricRef -> {
                val reference = current.metric
                val target = declared[reference]
                require(target != null) {
                    "derived metric [$alias] must reference a metric declared before it, but was [$reference]."
                }
                val nonNumeric = nonNumericType(target)
                require(nonNumeric == null) {
                    "derived metric [$alias] cannot reference $nonNumeric metric [$reference]."
                }
            }

            is DerivedExpression.Constant -> Unit

            is DerivedExpression.Binary -> {
                pending.addLast(PendingDerivedExpression(current.left, depth + 1))
                pending.addLast(PendingDerivedExpression(current.right, depth + 1))
            }

            is DerivedExpression.Window -> {
                counter.windows++
                require(counter.windows <= DerivedExpression.MAX_WINDOWS) {
                    "an aggregation must compute at most ${DerivedExpression.MAX_WINDOWS} windows."
                }
                current.requireValidWindow(alias, declared, groupBy)
            }
        }
    }
}

private data class PendingHavingExpression(
    val expression: HavingExpression,
    val depth: Int,
)

private fun requireValidHaving(having: HavingExpression?, groupBy: List<AggregationGroup>, metrics: List<AggregationMetric>) {
    if (having == null) {
        return
    }
    require(groupBy.isNotEmpty()) { "having requires at least one groupBy." }
    val metricAliases = metrics.mapTo(hashSetOf(), AggregationMetric::alias)
    val anyAliases = metrics.filterIsInstance<AggregationMetric.Any>().mapTo(hashSetOf(), AggregationMetric::alias)
    val edgeAliases = metrics.filterIsInstance<AggregationMetric.Edge>().mapTo(hashSetOf(), AggregationMetric::alias)
    val pending = ArrayDeque<PendingHavingExpression>()
    pending.addLast(PendingHavingExpression(having, 1))
    while (pending.isNotEmpty()) {
        val (current, depth) = pending.removeLast()
        require(depth <= AggregationQuery.MAX_EXPRESSION_DEPTH) {
            "having expression depth must be at most ${AggregationQuery.MAX_EXPRESSION_DEPTH}."
        }
        when (current) {
            is HavingExpression.Condition -> {
                requireValidHavingMetric(current.metric, metricAliases, anyAliases, edgeAliases)
                require(current.value.isFinite()) { "having condition [${current.metric}] value must be finite." }
            }

            is HavingExpression.Between -> {
                requireValidHavingMetric(current.metric, metricAliases, anyAliases, edgeAliases)
                require(current.lower.isFinite() && current.upper.isFinite()) {
                    "having between [${current.metric}] bounds must be finite."
                }
                require(current.lower <= current.upper) {
                    "having between [${current.metric}] lower bound must not exceed upper bound."
                }
            }

            is HavingExpression.In -> {
                requireValidHavingMetric(current.metric, metricAliases, anyAliases, edgeAliases)
                require(current.values.isNotEmpty()) { "having in [${current.metric}] values must not be empty." }
                require(current.values.all(Double::isFinite)) {
                    "having in [${current.metric}] values must be finite."
                }
            }

            is HavingExpression.IsNull -> requireValidHavingMetric(
                current.metric,
                metricAliases,
                anyAliases,
                edgeAliases
            )

            is HavingExpression.And -> {
                require(current.operands.isNotEmpty()) { "having AND operands must not be empty." }
                current.operands.forEach {
                    pending.addLast(PendingHavingExpression(it, depth + 1))
                }
            }

            is HavingExpression.Or -> {
                require(current.operands.isNotEmpty()) { "having OR operands must not be empty." }
                current.operands.forEach {
                    pending.addLast(PendingHavingExpression(it, depth + 1))
                }
            }
        }
    }
}

private fun requireValidHavingMetric(
    metric: String,
    metricAliases: Set<String>,
    anyAliases: Set<String>,
    edgeAliases: Set<String>,
) {
    require(metric in metricAliases) { "having condition [$metric] must reference a declared metric alias." }
    require(metric !in anyAliases) { "having condition [$metric] cannot reference ANY metric." }
    require(metric !in edgeAliases) { "having condition [$metric] cannot reference FIRST or LAST metric." }
}
