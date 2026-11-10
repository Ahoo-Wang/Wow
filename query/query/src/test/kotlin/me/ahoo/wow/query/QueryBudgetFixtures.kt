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

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryType
import org.junit.jupiter.api.assertThrows

/** What the budget's tests share: a budget of the HTTP entry and the checks a query is expected to pass or fail. */
internal object QueryBudgetFixtures {
    fun budget(
        maxListSize: Int = 1000,
        maxPageSize: Int = 100,
        maxPageWindow: Long = 10_000,
        maxFilterNodes: Int = QueryBudget.DEFAULT_MAX_FILTER_NODES,
        maxFilterValues: Int = 1000,
        allowExpensiveOperators: Boolean = false,
    ) = QueryBudget(
        label = QueryBudget.HTTP_LABEL,
        maxListSize = maxListSize,
        maxPageSize = maxPageSize,
        maxPageWindow = maxPageWindow,
        maxFilterNodes = maxFilterNodes,
        maxFilterValues = maxFilterValues,
        allowExpensiveOperators = allowExpensiveOperators,
    )

    fun expectRejected(
        queryType: QueryType,
        query: Any,
        budget: QueryBudget = budget(),
        scope: FilterExpression = MatchAllFilter,
    ) {
        assertThrows<QueryRequestException> { budget.checkFor(queryType, query, scope) }
    }

    fun expectAllowed(
        queryType: QueryType,
        query: Any,
        budget: QueryBudget = budget(),
        scope: FilterExpression = MatchAllFilter,
    ) {
        budget.checkFor(queryType, query, scope)
    }

    /** Routes a test query to the typed check its gateway method uses. */
    private fun QueryBudget.checkFor(queryType: QueryType, query: Any, scope: FilterExpression) = when (queryType) {
        QueryType.SINGLE -> check(query as SingleQuery, scope)
        QueryType.LIST -> check(query as ListQuery, scope)
        QueryType.PAGED -> check(query as PagedQuery, scope)
        QueryType.CURSOR -> check(query as CursorQuery, scope)
        QueryType.COUNT -> check(query as CountQuery, scope)
        QueryType.AGGREGATION -> check(query as AggregationQuery, scope)
        QueryType.STATE, QueryType.TRACE -> error("A state read has no query budget.")
    }
}
