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
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.query.filter.hasArithmeticExpression
import me.ahoo.wow.query.filter.isExpensive
import me.ahoo.wow.query.filter.isMatchAll
import me.ahoo.wow.query.filter.valueCount
import me.ahoo.wow.query.filter.walkFilterNodes
import me.ahoo.wow.query.filter.walkHavingNodes

/**
 * Size limits and expensive-operator gates for one [QueryEntry], checked at admission step 0: against the query as
 * the caller submitted it plus the scope from the edge, before any extension rewrites it. Policy conditions, the
 * model's default scope and cursor tie-breakers therefore never count, and a policy may use a gated operator.
 *
 * A limit of `0` disables that limit. [label] opens every message, e.g. `HTTP page size[0] ...`.
 *
 * [maxResidualGroups] is metered during execution instead: when a storage cannot run an aggregation's HAVING or
 * metric sort natively, the core reads every group to compute it, and fails once more than this many groups arrive.
 */
@Suppress("TooManyFunctions")
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
        require(query.size >= 1 && (maxPageSize == 0 || query.size <= maxPageSize)) {
            "$label cursor size[${query.size}] must be between 1 and $maxPageSize."
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
        require(allowExpensiveOperators || query.elements.isEmpty()) {
            "$label aggregation elements are disabled because expensive operators are not allowed."
        }
        val metricAliases = query.metrics.mapTo(hashSetOf(), AggregationMetric::alias)
        require(allowExpensiveOperators || query.sort.none { it.field.path in metricAliases }) {
            "$label aggregation metric sorting is disabled because expensive operators are not allowed."
        }
        require(allowExpensiveOperators || query.metrics.none(AggregationMetric::hasArithmeticExpression)) {
            "$label aggregation arithmetic expressions are disabled because expensive operators are not allowed."
        }
        query.having?.let { checkHaving(it, filterNodes) }
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
        require(limit >= minimum && (maxListSize == 0 || limit <= maxListSize)) {
            "$label $queryName query limit[$limit] must be between $minimum and $maxListSize."
        }
    }

    private fun checkPage(query: IPagedQuery) {
        val pagination = query.pagination
        require(pagination.index >= 1) { "$label page index[${pagination.index}] must be greater than or equal to 1." }
        require(pagination.size >= 1 && (maxPageSize == 0 || pagination.size <= maxPageSize)) {
            "$label page size[${pagination.size}] must be between 1 and $maxPageSize."
        }
        val window = pagination.index.toLong() * pagination.size.toLong()
        require(maxPageWindow == 0L || window <= maxPageWindow) {
            "$label page window[$window] must not exceed $maxPageWindow."
        }
        val offset = (pagination.index.toLong() - 1) * pagination.size
        require(offset <= Int.MAX_VALUE) {
            "$label page offset[$offset] must not exceed ${Int.MAX_VALUE}."
        }
    }

    private fun checkFilters(filters: List<FilterExpression>, rejectMatchAll: Boolean): Int {
        var nodes = 0
        filters.walkFilterNodes().forEach { current ->
            nodes++
            require(maxFilterNodes == 0 || nodes <= maxFilterNodes) {
                "$label query filter nodes[$nodes] must not exceed $maxFilterNodes."
            }
            checkFilterNode(current)
        }
        require(!rejectMatchAll || !filters.all { it.isMatchAll() }) {
            "$label counting query must not match all documents."
        }
        return nodes
    }

    private fun checkFilterNode(filter: FilterExpression) {
        require(allowExpensiveOperators || !filter.isExpensive()) {
            "$label query operator[${filter.operator}] is disabled because expensive operators are not allowed."
        }
        val valueCount = filter.valueCount()
        if (valueCount != null) {
            require(maxFilterValues == 0 || valueCount <= maxFilterValues) {
                "$label query filter values[$valueCount] must not exceed $maxFilterValues."
            }
        }
    }

    private fun checkHaving(having: HavingExpression, sharedNodes: Int) {
        var nodes = sharedNodes
        having.walkHavingNodes().forEach { current ->
            nodes++
            require(maxFilterNodes == 0 || nodes <= maxFilterNodes) {
                "$label filter and having nodes[$nodes] must not exceed $maxFilterNodes."
            }
            val valueCount = current.valueCount()
            if (valueCount > 0) {
                require(maxFilterValues == 0 || valueCount <= maxFilterValues) {
                    "$label having values[$valueCount] must not exceed $maxFilterValues."
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_FILTER_NODES: Int = 128
        const val DEFAULT_MAX_RESIDUAL_GROUPS: Int = 10_000
        const val HTTP_LABEL: String = "HTTP"

        /** The HTTP budget with the default limits. */
        val HTTP_DEFAULT: QueryBudget = QueryBudget(HTTP_LABEL)
    }
}
