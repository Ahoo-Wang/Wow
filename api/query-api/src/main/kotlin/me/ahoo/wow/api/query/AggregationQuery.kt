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
    val path: QueryField<*>,
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
            path: QueryField<*>,
            filter: FilterExpression = MatchAllFilter,
        ): AggregationElement = AggregationElement(path, filter)
    }
}

internal fun requireAggregationAlias(alias: String) {
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
