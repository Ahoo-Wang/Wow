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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.inputExpression
import me.ahoo.wow.api.query.spec.GroupSpec
import me.ahoo.wow.api.query.spec.MetricSpec
import me.ahoo.wow.api.query.spec.OperatorCost
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.query.filter.hasArithmeticExpression
import me.ahoo.wow.query.filter.isExpensive
import me.ahoo.wow.query.filter.isMatchAll
import me.ahoo.wow.query.filter.valueCount
import me.ahoo.wow.query.filter.walkFilterNodes
import me.ahoo.wow.query.filter.walkHavingNodes
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.QueryViolation.Companion.FILTER
import me.ahoo.wow.query.schema.requireValid

/**
 * Size limits and expensive-operator gates for one [QueryEntry], checked at admission step 0: against the query as
 * the caller submitted it plus the caller's scope from the edge, before any extension rewrites it. A route's
 * selection (what a load route names in its URL), policy conditions, the model's default scope and cursor
 * tie-breakers therefore never count, and a policy may use a gated operator.
 *
 * A limit of `0` disables that limit. Every rejection is a request [QueryViolation] whose text [label] opens, e.g.
 * `HTTP page size[0] ...`.
 *
 * [maxResidualGroups] is metered during execution instead: when a storage cannot run an aggregation's HAVING or
 * metric sort natively, the core reads every group to compute it, and fails once more than this many groups arrive.
 */
class QueryBudget(
    val label: String,
    val maxListSize: Int = 1000,
    val maxPageSize: Int = 100,
    val maxPageWindow: Long = 10_000,
    val maxFilterNodes: Int = DEFAULT_MAX_FILTER_NODES,
    val maxFilterValues: Int = 1000,
    val allowExpensiveOperators: Boolean = true,
    val maxResidualGroups: Int = DEFAULT_MAX_RESIDUAL_GROUPS,
) {
    init {
        require(maxListSize >= 0) { "maxListSize must be greater than or equal to 0." }
        require(maxPageSize >= 0) { "maxPageSize must be greater than or equal to 0." }
        require(maxPageWindow >= 0) { "maxPageWindow must be greater than or equal to 0." }
        require(maxFilterNodes >= 0) { "maxFilterNodes must be greater than or equal to 0." }
        require(maxFilterValues >= 0) { "maxFilterValues must be greater than or equal to 0." }
        require(maxResidualGroups >= 0) { "maxResidualGroups must be greater than or equal to 0." }
    }

    /**
     * The list size applied to a list query that sends none, from the configured [default]: at most [maxListSize],
     * and `null` (none applied, `0` keeps meaning unlimited) when either is `0`.
     */
    fun listDefault(default: Int): Int? =
        if (default <= 0 || maxListSize == 0) null else default.coerceAtMost(maxListSize)

    fun check(query: ISingleQuery, scope: FilterExpression = MatchAllFilter) {
        checkFilter(query.filter, scope, counting = false)
    }

    fun check(query: IListQuery, scope: FilterExpression = MatchAllFilter) {
        checkResultSize(query.limit, "list")
        checkFilter(query.filter, scope, counting = false)
    }

    fun check(query: IPagedQuery, scope: FilterExpression = MatchAllFilter) {
        checkPage(query)
        checkFilter(query.filter, scope, counting = true)
    }

    fun check(query: ICursorQuery, scope: FilterExpression = MatchAllFilter) {
        requireValid(query.size >= 1 && (maxPageSize == 0 || query.size <= maxPageSize)) {
            QueryViolation.SizeOutOfRange(label, "cursor size", query.size.toLong(), 1, maxPageSize.toLong(), "size")
        }
        checkFilter(query.filter, scope, counting = false)
    }

    fun check(query: AggregationQuery, scope: FilterExpression = MatchAllFilter) {
        checkResultSize(query.limit, "aggregation")
        val filterNodes = checkFilters(
            listOf(query.filter) +
                query.elements.map(AggregationElement::filter) +
                query.metrics.map(AggregationMetric::filter).filter { it !== MatchAllFilter } +
                scope.asScopeFilters(),
            rejectMatchAll = false,
        )
        requireValid(allowExpensiveOperators || query.elements.isEmpty()) {
            QueryViolation.ExpensiveOperatorDisabled(
                label,
                "aggregation elements",
                plural = true,
                location = "elements"
            )
        }
        val metricAliases = query.metrics.mapTo(hashSetOf(), AggregationMetric::alias)
        requireValid(allowExpensiveOperators || query.sort.none { it.field.path in metricAliases }) {
            QueryViolation.ExpensiveOperatorDisabled(label, "aggregation metric sorting", location = "sort")
        }
        if (!allowExpensiveOperators) {
            query.metrics.forEach(::checkMetricCost)
            query.groupBy.forEach(::checkGroupCost)
        }
        query.having?.let { checkHaving(it, filterNodes) }
    }

    /** Rejects [metric] when its [MetricSpec] cost is expensive: arithmetic, derived, FIRST and LAST. */
    private fun checkMetricCost(metric: AggregationMetric) {
        if (metric.spec.cost(metric) != OperatorCost.EXPENSIVE) return
        val arithmetic = metric.hasArithmeticExpression()
        val construct = if (arithmetic) "aggregation arithmetic expressions" else "aggregation metric[${metric.spec}]"
        throw QueryViolation.ExpensiveOperatorDisabled(label, construct, arithmetic, "metrics").rejection()
    }

    /** Rejects [group] when its [GroupSpec] cost is expensive: an expression input, a dense fill or a date part. */
    private fun checkGroupCost(group: AggregationGroup) {
        if (group.spec.cost(group) != OperatorCost.EXPENSIVE) return
        val (construct, plural) = when {
            group.inputExpression != null -> "aggregation expression groups" to true
            group.spec.baseCost == OperatorCost.EXPENSIVE -> "aggregation group[${group.spec}]" to false
            else -> "aggregation dense fill" to false
        }
        throw QueryViolation.ExpensiveOperatorDisabled(label, construct, plural, "groupBy").rejection()
    }

    fun checkCount(filter: FilterExpression, scope: FilterExpression = MatchAllFilter) {
        checkFilter(filter, scope, counting = true)
    }

    private fun FilterExpression.asScopeFilters(): List<FilterExpression> =
        if (this === MatchAllFilter) emptyList() else listOf(this)

    private fun checkFilter(filter: FilterExpression, scope: FilterExpression, counting: Boolean) {
        checkFilters(listOf(filter) + scope.asScopeFilters(), rejectMatchAll = !allowExpensiveOperators && counting)
    }

    private fun checkResultSize(limit: Int, queryName: String) {
        val minimum = if (maxListSize == 0) 0 else 1
        requireValid(limit >= minimum && (maxListSize == 0 || limit <= maxListSize)) {
            QueryViolation.SizeOutOfRange(
                label,
                "$queryName query limit",
                limit.toLong(),
                minimum.toLong(),
                maxListSize.toLong(),
                "limit",
            )
        }
    }

    private fun checkPage(query: IPagedQuery) {
        val pagination = query.pagination
        requireValid(pagination.index >= 1) {
            QueryViolation.SizeOutOfRange(label, "page index", pagination.index.toLong(), 1, null, PAGINATION_INDEX)
        }
        requireValid(pagination.size >= 1 && (maxPageSize == 0 || pagination.size <= maxPageSize)) {
            QueryViolation.SizeOutOfRange(
                label,
                "page size",
                pagination.size.toLong(),
                1,
                maxPageSize.toLong(),
                PAGINATION_SIZE,
            )
        }
        val window = pagination.index.toLong() * pagination.size.toLong()
        requireValid(maxPageWindow == 0L || window <= maxPageWindow) {
            QueryViolation.SizeOutOfRange(label, "page window", window, null, maxPageWindow, PAGINATION)
        }
        val offset = (pagination.index.toLong() - 1) * pagination.size
        requireValid(offset <= Int.MAX_VALUE) {
            QueryViolation.SizeOutOfRange(label, "page offset", offset, null, Int.MAX_VALUE.toLong(), PAGINATION)
        }
    }

    private fun checkFilters(filters: List<FilterExpression>, rejectMatchAll: Boolean): Int {
        var nodes = 0
        filters.walkFilterNodes().forEach { current ->
            nodes++
            requireValid(maxFilterNodes == 0 || nodes <= maxFilterNodes) {
                QueryViolation.FilterTooLarge(label, "query filter nodes", nodes, maxFilterNodes, FILTER)
            }
            checkFilterNode(current)
        }
        requireValid(!rejectMatchAll || !filters.all { it.isMatchAll() }) { QueryViolation.CountRequiresFilter(label) }
        return nodes
    }

    private fun checkFilterNode(filter: FilterExpression) {
        requireValid(allowExpensiveOperators || !filter.isExpensive()) {
            QueryViolation.ExpensiveOperatorDisabled(label, "query operator[${filter.operator}]", location = FILTER)
        }
        val valueCount = filter.valueCount()
        if (valueCount != null) {
            requireValid(maxFilterValues == 0 || valueCount <= maxFilterValues) {
                QueryViolation.FilterTooLarge(label, "query filter values", valueCount, maxFilterValues, FILTER)
            }
        }
    }

    private fun checkHaving(having: HavingExpression, sharedNodes: Int) {
        var nodes = sharedNodes
        having.walkHavingNodes().forEach { current ->
            nodes++
            requireValid(maxFilterNodes == 0 || nodes <= maxFilterNodes) {
                QueryViolation.FilterTooLarge(label, "filter and having nodes", nodes, maxFilterNodes, HAVING)
            }
            val valueCount = current.valueCount()
            if (valueCount > 0) {
                requireValid(maxFilterValues == 0 || valueCount <= maxFilterValues) {
                    QueryViolation.FilterTooLarge(label, "having values", valueCount, maxFilterValues, HAVING)
                }
            }
        }
    }

    companion object {
        private const val PAGINATION = "pagination"
        private const val PAGINATION_INDEX = "pagination.index"
        private const val PAGINATION_SIZE = "pagination.size"
        private const val HAVING = "having"
        const val DEFAULT_MAX_FILTER_NODES: Int = 128
        const val DEFAULT_MAX_RESIDUAL_GROUPS: Int = 10_000
        const val HTTP_LABEL: String = "HTTP"

        /** The HTTP budget with the default limits. */
        val HTTP_DEFAULT: QueryBudget = QueryBudget(HTTP_LABEL)
    }
}
