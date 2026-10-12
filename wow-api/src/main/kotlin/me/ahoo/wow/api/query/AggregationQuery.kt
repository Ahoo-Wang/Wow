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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.serialization.MissingTypeImpl

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class AggregationQuery(
    override val filter: FilterExpression = MatchAllFilter,
    @get:ArraySchema(maxItems = MAX_ELEMENTS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val elements: List<AggregationElement> = emptyList(),
    @get:ArraySchema(maxItems = MAX_GROUPS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val groupBy: List<AggregationGroup> = emptyList(),
    @get:ArraySchema(minItems = 1, maxItems = MAX_METRICS, schema = Schema(implementation = AggregationMetric::class))
    val metrics: List<AggregationMetric>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val sort: List<Sort> = emptyList(),
    @get:Schema(defaultValue = DEFAULT_LIMIT_TEXT, minimum = "1", maximum = MAX_LIMIT_TEXT)
    val limit: Int = DEFAULT_LIMIT,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
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

    override fun withFilter(newFilter: FilterExpression): AggregationQuery = copy(filter = newFilter)

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

        @Deprecated("Scheduled for removal in 10.0.0. Use Sort.MAX_FIELDS.", ReplaceWith("Sort.MAX_FIELDS"))
        const val MAX_SORT_FIELDS: Int = Sort.MAX_FIELDS

        const val MAX_EXPRESSION_DEPTH: Int = 8
        const val MAX_EXPRESSION_NODES: Int = 256
        private const val DEFAULT_LIMIT_TEXT = "100"
        private const val MAX_LIMIT_TEXT = "10000"
    }
}

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class AggregationElement(
    val path: QueryField,
    val filter: FilterExpression = MatchAllFilter,
) {
    init {
        require(filter.containsElementUnsupportedFilter(fieldSearch = false).not()) {
            "Aggregation element filter cannot contain root filters."
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(AggregationGroup.Terms::class, name = "TERMS"),
    JsonSubTypes.Type(AggregationGroup.Histogram::class, name = "HISTOGRAM"),
    JsonSubTypes.Type(AggregationGroup.DateHistogram::class, name = "DATE_HISTOGRAM"),
    JsonSubTypes.Type(AggregationGroup.DatePart::class, name = "DATE_PART"),
)
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
    data class Terms(
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        override val field: QueryField? = null,
        override val alias: String,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val missingKey: String? = null,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val expression: AggregationExpression? = null,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
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
    }

    /** Buckets the numeric value of [field], or of [expression] when the group names one instead. */
    data class Histogram(
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        override val field: QueryField? = null,
        override val alias: String,
        @get:Schema(minimum = "0", exclusiveMinimum = true)
        val interval: Double,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val expression: AggregationExpression? = null,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
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
    }

    data class DateHistogram(
        override val field: QueryField,
        override val alias: String,
        val unit: AggregationDateUnit,
        val timeZone: String = "UTC",
        @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = FalseValueFilter::class)
        val dense: Boolean = false,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        override val scope: AggregationGroupScope? = null,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            zoneIdOf(timeZone)
        }
    }

    /**
     * Groups by one calendar part of the field's instant in [timeZone], such as the weekday or the hour, so records
     * from different days fall into one bucket. Keys are integers from [AggregationDatePart]'s fixed domain; [dense]
     * fills every key of that domain that has no records with the empty value of each metric.
     */
    data class DatePart(
        override val field: QueryField,
        override val alias: String,
        val part: AggregationDatePart,
        val timeZone: String = "UTC",
        @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = FalseValueFilter::class)
        val dense: Boolean = false,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        override val scope: AggregationGroupScope? = null,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            zoneIdOf(timeZone)
        }
    }
}

/** The computed input of a TERMS or HISTOGRAM group in place of its field, or `null`. */
val AggregationGroup.inputExpression: AggregationExpression?
    get() = when (this) {
        is AggregationGroup.Terms -> expression
        is AggregationGroup.Histogram -> expression
        is AggregationGroup.DateHistogram, is AggregationGroup.DatePart -> null
    }

/** A calendar part of an instant, with its fixed integer domain `[min, max]`. */
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
@MissingTypeImpl(AggregationExpression.Field::class)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(AggregationExpression.Field::class, name = "FIELD"),
    JsonSubTypes.Type(AggregationExpression.Constant::class, name = "CONSTANT"),
    JsonSubTypes.Type(AggregationExpression.Binary::class, name = "BINARY"),
    JsonSubTypes.Type(AggregationExpression.DateDiff::class, name = "DATE_DIFF"),
)
@Schema(
    oneOf = [
        AggregationExpression.Field::class,
        AggregationExpression.Constant::class,
        AggregationExpression.Binary::class,
        AggregationExpression.DateDiff::class,
    ],
    discriminatorProperty = QueryProtocol.Polymorphic.TYPE,
)
sealed interface AggregationExpression {
    data class Field(val field: QueryField) : AggregationExpression

    data class Constant(val value: Double) : AggregationExpression {
        init {
            require(value.isFinite()) { "aggregation constant must be finite." }
        }
    }

    data class Binary(
        val operator: AggregationExpressionOperator,
        val left: AggregationExpression,
        val right: AggregationExpression,
    ) : AggregationExpression

    /**
     * The elapsed time from the instant in [from] to the instant in [to], `to − from`, in [unit] as a decimal: negative
     * when [to] is earlier, and no contribution when either instant is absent. Both fields must be single-valued
     * temporal fields; each is read in its own temporal encoding.
     */
    data class DateDiff(
        val from: QueryField,
        val to: QueryField,
        val unit: DateDiffUnit,
    ) : AggregationExpression
}

/**
 * A fixed-length unit of elapsed time. Calendar units (months, years) have no fixed length and depend on a time zone,
 * so they are not offered; a `DAY` is exactly 24 hours.
 */
enum class DateDiffUnit(val millis: Long) {
    SECOND(1_000L),
    MINUTE(60_000L),
    HOUR(3_600_000L),
    DAY(86_400_000L),
}

/** The fields an expression reads, in tree order. */
val AggregationExpression.fields: List<QueryField>
    get() = when (this) {
        is AggregationExpression.Field -> listOf(field)
        is AggregationExpression.Constant -> emptyList()
        is AggregationExpression.Binary -> left.fields + right.fields
        is AggregationExpression.DateDiff -> listOf(from, to)
    }

enum class AggregationExpressionOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(AggregationMetric.Count::class, name = "COUNT"),
    JsonSubTypes.Type(AggregationMetric.Numeric::class, name = "NUMERIC"),
    JsonSubTypes.Type(AggregationMetric.Any::class, name = "ANY"),
    JsonSubTypes.Type(AggregationMetric.DistinctCount::class, name = "DISTINCT_COUNT"),
    JsonSubTypes.Type(AggregationMetric.Percentile::class, name = "PERCENTILE"),
    JsonSubTypes.Type(AggregationMetric.Derived::class, name = "DERIVED"),
    JsonSubTypes.Type(AggregationMetric.First::class, name = "FIRST"),
    JsonSubTypes.Type(AggregationMetric.Last::class, name = "LAST"),
)
@Schema(
    oneOf = [
        AggregationMetric.Count::class,
        AggregationMetric.Numeric::class,
        AggregationMetric.Any::class,
        AggregationMetric.DistinctCount::class,
        AggregationMetric.Percentile::class,
        AggregationMetric.Derived::class,
        AggregationMetric.First::class,
        AggregationMetric.Last::class,
    ],
    discriminatorProperty = QueryProtocol.Polymorphic.TYPE,
)
sealed interface AggregationMetric {
    @get:Schema(accessMode = Schema.AccessMode.READ_WRITE)
    val alias: String

    @get:Schema(accessMode = Schema.AccessMode.READ_WRITE)
    val filter: FilterExpression

    data class Count(
        override val alias: String,
        @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = MatchAllFilterValueFilter::class)
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }
    }

    data class Numeric(
        val function: AggregationFunction,
        val expression: AggregationExpression,
        override val alias: String,
        @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = MatchAllFilterValueFilter::class)
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }
    }

    data class Any(
        val field: QueryField,
        override val alias: String,
        @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = MatchAllFilterValueFilter::class)
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }
    }

    data class DistinctCount(
        val expression: AggregationExpression,
        override val alias: String,
        @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = MatchAllFilterValueFilter::class)
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }
    }

    data class Percentile(
        val expression: AggregationExpression,
        @get:Schema(minimum = "0", exclusiveMinimum = true, maximum = "100", exclusiveMaximum = true)
        val percentile: Double,
        override val alias: String,
        @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = MatchAllFilterValueFilter::class)
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
            require(percentile.isFinite() && percentile > 0.0 && percentile < 100.0) {
                "percentile must be finite and within (0, 100)."
            }
        }
    }

    data class Derived(
        override val alias: String,
        val expression: DerivedExpression,
    ) : AggregationMetric {
        @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = MatchAllFilterValueFilter::class)
        override val filter: FilterExpression get() = MatchAllFilter

        init {
            requireAggregationAlias(alias)
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

    data class First(
        override val field: QueryField,
        override val alias: String,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        override val orderBy: QueryField? = null,
        @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = MatchAllFilterValueFilter::class)
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric, Edge {
        init {
            requireAggregationAlias(alias)
        }
    }

    data class Last(
        override val field: QueryField,
        override val alias: String,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        override val orderBy: QueryField? = null,
        @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = MatchAllFilterValueFilter::class)
        override val filter: FilterExpression = MatchAllFilter,
    ) : AggregationMetric, Edge {
        init {
            requireAggregationAlias(alias)
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(DerivedExpression.MetricRef::class, name = "METRIC_REF"),
    JsonSubTypes.Type(DerivedExpression.Constant::class, name = "CONSTANT"),
    JsonSubTypes.Type(DerivedExpression.Binary::class, name = "BINARY"),
    JsonSubTypes.Type(DerivedExpression.Total::class, name = "TOTAL"),
    JsonSubTypes.Type(DerivedExpression.Running::class, name = "RUNNING"),
    JsonSubTypes.Type(DerivedExpression.Previous::class, name = "PREVIOUS"),
    JsonSubTypes.Type(DerivedExpression.Rank::class, name = "RANK"),
)
@Schema(
    oneOf = [
        DerivedExpression.MetricRef::class,
        DerivedExpression.Constant::class,
        DerivedExpression.Binary::class,
        DerivedExpression.Total::class,
        DerivedExpression.Running::class,
        DerivedExpression.Previous::class,
        DerivedExpression.Rank::class,
    ],
    discriminatorProperty = QueryProtocol.Polymorphic.TYPE,
)
sealed interface DerivedExpression {
    data class MetricRef(val metric: String) : DerivedExpression

    data class Constant(val value: Double) : DerivedExpression {
        init {
            require(value.isFinite()) { "derived constant must be finite." }
        }
    }

    data class Binary(
        val operator: AggregationExpressionOperator,
        val left: DerivedExpression,
        val right: DerivedExpression,
    ) : DerivedExpression

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
    data class Total(
        override val metric: String,
        @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
        val within: List<String> = emptyList(),
    ) : Window

    /**
     * [metric] accumulated along [order] over the groups that share this group's [within] keys: the [function] of
     * every group up to and including this one, or of the last [frame] of them. Groups tied on [order] follow the
     * remaining group keys ascending, in `groupBy` order. `null` when every value in the frame is `null`. SUM accepts
     * only COUNT and SUM; AVG, a moving average with [frame], accepts any numeric metric.
     */
    data class Running(
        override val metric: String,
        /** A group alias or a non-derived numeric metric alias, with its direction. */
        val order: Sort,
        @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
        val within: List<String> = emptyList(),
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:Schema(minimum = "1", maximum = MAX_FRAME_TEXT)
        val frame: Int? = null,
        val function: RunningFunction = RunningFunction.SUM,
    ) : Window {
        init {
            require(frame == null || frame in 1..MAX_FRAME) { "running frame must be between 1 and $MAX_FRAME." }
        }
    }

    /**
     * [metric] of the group [shift] steps earlier along the `DATE_HISTOGRAM` group [along], every other group key
     * equal: a step is one bucket of [along], or one [period] (such as `YEAR` for the same period last year). The
     * earlier groups are read over the query's own range of [along]'s field, shifted. `null` when that group has no
     * records.
     */
    data class Previous(
        override val metric: String,
        val along: String,
        @get:Schema(defaultValue = "1", minimum = "1", maximum = MAX_SHIFT_TEXT)
        val shift: Int = 1,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        val period: AggregationDateUnit? = null,
    ) : Window {
        init {
            require(shift in 1..MAX_SHIFT) { "previous shift must be between 1 and $MAX_SHIFT." }
        }
    }

    /**
     * The dense rank of [metric] among the groups that share this group's [within] keys, in [direction]: tied values
     * share a rank and ranks have no gaps. `null` for a group whose value is `null`.
     */
    data class Rank(
        override val metric: String,
        @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
        val within: List<String> = emptyList(),
        val direction: Sort.Direction = Sort.Direction.DESC,
    ) : Window

    companion object {
        /** The most windows one aggregation may compute. */
        const val MAX_WINDOWS: Int = 8
        const val MAX_FRAME: Int = AggregationQuery.MAX_LIMIT
        const val MAX_SHIFT: Int = 1_000
        private const val MAX_FRAME_TEXT = "10000"
        private const val MAX_SHIFT_TEXT = "1000"
    }
}

/** The window functions of [DerivedExpression.Window], as a storage declares and the descriptor lists them. */
enum class WindowFunction {
    TOTAL,
    RUNNING,
    PREVIOUS,
    RANK,
}

/** What a [DerivedExpression.Running] window computes over its frame. */
enum class RunningFunction {
    SUM,
    AVG,
}

/** The function of this window. */
val DerivedExpression.Window.windowFunction: WindowFunction
    get() = when (this) {
        is DerivedExpression.Total -> WindowFunction.TOTAL
        is DerivedExpression.Running -> WindowFunction.RUNNING
        is DerivedExpression.Previous -> WindowFunction.PREVIOUS
        is DerivedExpression.Rank -> WindowFunction.RANK
    }

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(HavingExpression.Condition::class, name = "CONDITION"),
    JsonSubTypes.Type(HavingExpression.Between::class, name = "BETWEEN"),
    JsonSubTypes.Type(HavingExpression.In::class, name = "IN"),
    JsonSubTypes.Type(HavingExpression.IsNull::class, name = "IS_NULL"),
    JsonSubTypes.Type(HavingExpression.And::class, name = "AND"),
    JsonSubTypes.Type(HavingExpression.Or::class, name = "OR"),
)
@Schema(
    oneOf = [
        HavingExpression.Condition::class,
        HavingExpression.Between::class,
        HavingExpression.In::class,
        HavingExpression.IsNull::class,
        HavingExpression.And::class,
        HavingExpression.Or::class,
    ],
    discriminatorProperty = QueryProtocol.Polymorphic.TYPE,
)
sealed interface HavingExpression {
    data class Condition(val metric: String, val operator: ComparisonOperator, val value: Double) : HavingExpression

    data class Between(val metric: String, val lower: Double, val upper: Double) : HavingExpression

    data class In(
        val metric: String,
        @get:ArraySchema(minItems = 1)
        val values: List<Double>,
    ) : HavingExpression

    data class IsNull(val metric: String, val negated: Boolean = false) : HavingExpression

    data class And(
        @get:ArraySchema(minItems = 1)
        val operands: List<HavingExpression>,
    ) : HavingExpression

    data class Or(
        @get:ArraySchema(minItems = 1)
        val operands: List<HavingExpression>,
    ) : HavingExpression
}

enum class ComparisonOperator {
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE,
}

enum class AggregationFunction {
    SUM,
    AVG,
    MIN,
    MAX,
    STDDEV,
    VARIANCE,
}

/** Where a group reads its field when the query expands elements; see [AggregationGroup.scope]. */
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

/**
 * Omits the [MatchAllFilter] default of metric-level `filter` properties so unfiltered queries
 * keep their original JSON shape.
 *
 * Jackson 3 resolves `JsonInclude.Include.CUSTOM` value filters through
 * `valueFilterInstance.equals(propertyValue)` (see `BeanPropertyWriter.serializeAsProperty`),
 * so the omission predicate is expressed via [equals]. Kotlin constructor defaults cannot be
 * honored by `JsonInclude.Include.NON_DEFAULT` here: `jackson-module-kotlin` does not expose
 * creator defaults to inclusion checks.
 */
internal class MatchAllFilterValueFilter {
    override fun equals(other: Any?): Boolean = other === MatchAllFilter

    override fun hashCode(): Int = MatchAllFilterValueFilter::class.hashCode()
}

/**
 * Omits the `false` default of [AggregationGroup.DateHistogram.dense] so non-dense queries
 * keep their original JSON shape. Constructor defaults cannot use
 * [JsonInclude.Include.NON_DEFAULT] here for the reason documented on
 * [MatchAllFilterValueFilter].
 */
internal class FalseValueFilter {
    override fun equals(other: Any?): Boolean = other == java.lang.Boolean.FALSE

    override fun hashCode(): Int = FalseValueFilter::class.hashCode()
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
