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
import java.time.ZoneId

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
    @get:ArraySchema(maxItems = MAX_SORT_FIELDS)
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
        require(sort.size <= MAX_SORT_FIELDS) { "sort must contain at most $MAX_SORT_FIELDS fields." }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT." }
        require(groupBy.isNotEmpty() || sort.isEmpty()) { "sort requires at least one groupBy." }
        metrics.requireValidExpressions()
        metrics.requireValidDerivedMetrics()
        requireValidHaving(having, groupBy, metrics)

        val aliases = groupBy.map(AggregationGroup::alias) + metrics.map(AggregationMetric::alias)
        require(aliases.distinct().size == aliases.size) { "aggregation aliases must be unique." }
        val sortFields = sort.map { it.field.path }
        require(sortFields.distinct().size == sortFields.size) { "sort fields must be unique." }
        require(sortFields.all(aliases::contains)) { "sort fields must reference aggregation aliases." }
        require(effectiveSort().size <= MAX_SORT_FIELDS) {
            "effective sort must contain at most $MAX_SORT_FIELDS fields."
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
        const val MAX_SORT_FIELDS: Int = 32
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
        require(filter.containsElementUnsupportedFilter().not()) {
            "Aggregation element filter cannot contain root filters."
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(AggregationGroup.Terms::class, name = "TERMS"),
    JsonSubTypes.Type(AggregationGroup.Histogram::class, name = "HISTOGRAM"),
    JsonSubTypes.Type(AggregationGroup.DateHistogram::class, name = "DATE_HISTOGRAM"),
)
sealed interface AggregationGroup {
    val field: QueryField
    val alias: String

    data class Terms(
        override val field: QueryField,
        override val alias: String,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
        }
    }

    data class Histogram(
        override val field: QueryField,
        override val alias: String,
        @get:Schema(minimum = "0", exclusiveMinimum = true)
        val interval: Double,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
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
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            ZoneId.of(timeZone)
        }
    }
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
)
@Schema(
    oneOf = [
        AggregationExpression.Field::class,
        AggregationExpression.Constant::class,
        AggregationExpression.Binary::class,
    ],
    discriminatorProperty = QueryProtocol.Polymorphic.TYPE,
)
interface AggregationExpression {
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
)
@Schema(
    oneOf = [
        AggregationMetric.Count::class,
        AggregationMetric.Numeric::class,
        AggregationMetric.Any::class,
        AggregationMetric.DistinctCount::class,
        AggregationMetric.Percentile::class,
        AggregationMetric.Derived::class,
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
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(DerivedExpression.MetricRef::class, name = "METRIC_REF"),
    JsonSubTypes.Type(DerivedExpression.Constant::class, name = "CONSTANT"),
    JsonSubTypes.Type(DerivedExpression.Binary::class, name = "BINARY"),
)
@Schema(
    oneOf = [
        DerivedExpression.MetricRef::class,
        DerivedExpression.Constant::class,
        DerivedExpression.Binary::class,
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

private data class PendingExpression(
    val expression: AggregationExpression,
    val depth: Int,
)

private fun List<AggregationMetric>.requireValidExpressions() {
    val pending = ArrayDeque<PendingExpression>()
    forEach { metric ->
        when (metric) {
            is AggregationMetric.Numeric -> pending.addLast(PendingExpression(metric.expression, 1))
            is AggregationMetric.DistinctCount -> pending.addLast(PendingExpression(metric.expression, 1))
            is AggregationMetric.Percentile -> pending.addLast(PendingExpression(metric.expression, 1))
            is AggregationMetric.Count, is AggregationMetric.Any, is AggregationMetric.Derived -> Unit
        }
    }
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
            -> Unit

            is AggregationExpression.Binary -> {
                pending.addLast(PendingExpression(expression.left, depth + 1))
                pending.addLast(PendingExpression(expression.right, depth + 1))
            }

            else -> throw IllegalArgumentException(
                "Unsupported aggregation expression: ${expression::class.java.name}.",
            )
        }
    }
}

private data class PendingDerivedExpression(
    val expression: DerivedExpression,
    val depth: Int,
)

private fun List<AggregationMetric>.requireValidDerivedMetrics() {
    val declared = LinkedHashMap<String, Boolean>()
    var nodes = 0
    forEach { metric ->
        if (metric is AggregationMetric.Derived) {
            nodes = metric.requireValidDerivedExpression(declared, nodes)
        }
        declared[metric.alias] = metric is AggregationMetric.Any
    }
}

private fun AggregationMetric.Derived.requireValidDerivedExpression(declared: Map<String, Boolean>, visitedNodes: Int): Int {
    val pending = ArrayDeque<PendingDerivedExpression>()
    pending.addLast(PendingDerivedExpression(expression, 1))
    var nodes = visitedNodes
    while (pending.isNotEmpty()) {
        val (current, depth) = pending.removeLast()
        require(depth <= AggregationQuery.MAX_EXPRESSION_DEPTH) {
            "derived expression depth must be at most ${AggregationQuery.MAX_EXPRESSION_DEPTH}."
        }
        nodes++
        require(nodes <= AggregationQuery.MAX_EXPRESSION_NODES) {
            "derived expressions must contain at most ${AggregationQuery.MAX_EXPRESSION_NODES} nodes."
        }
        when (current) {
            is DerivedExpression.MetricRef -> {
                val reference = current.metric
                require(reference in declared) {
                    "derived metric [$alias] must reference a metric declared before it, but was [$reference]."
                }
                require(!declared.getValue(reference)) {
                    "derived metric [$alias] cannot reference ANY metric [$reference]."
                }
            }

            is DerivedExpression.Constant -> Unit

            is DerivedExpression.Binary -> {
                pending.addLast(PendingDerivedExpression(current.left, depth + 1))
                pending.addLast(PendingDerivedExpression(current.right, depth + 1))
            }
        }
    }
    return nodes
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
    val pending = ArrayDeque<PendingHavingExpression>()
    pending.addLast(PendingHavingExpression(having, 1))
    while (pending.isNotEmpty()) {
        val (current, depth) = pending.removeLast()
        require(depth <= AggregationQuery.MAX_EXPRESSION_DEPTH) {
            "having expression depth must be at most ${AggregationQuery.MAX_EXPRESSION_DEPTH}."
        }
        when (current) {
            is HavingExpression.Condition -> {
                requireValidHavingMetric(current.metric, metricAliases, anyAliases)
                require(current.value.isFinite()) { "having condition [${current.metric}] value must be finite." }
            }

            is HavingExpression.Between -> {
                requireValidHavingMetric(current.metric, metricAliases, anyAliases)
                require(current.lower.isFinite() && current.upper.isFinite()) {
                    "having between [${current.metric}] bounds must be finite."
                }
                require(current.lower <= current.upper) {
                    "having between [${current.metric}] lower bound must not exceed upper bound."
                }
            }

            is HavingExpression.In -> {
                requireValidHavingMetric(current.metric, metricAliases, anyAliases)
                require(current.values.isNotEmpty()) { "having in [${current.metric}] values must not be empty." }
                require(current.values.all(Double::isFinite)) {
                    "having in [${current.metric}] values must be finite."
                }
            }

            is HavingExpression.IsNull -> requireValidHavingMetric(current.metric, metricAliases, anyAliases)

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

private fun requireValidHavingMetric(metric: String, metricAliases: Set<String>, anyAliases: Set<String>) {
    require(metric in metricAliases) { "having condition [$metric] must reference a declared metric alias." }
    require(metric !in anyAliases) { "having condition [$metric] cannot reference ANY metric." }
}
