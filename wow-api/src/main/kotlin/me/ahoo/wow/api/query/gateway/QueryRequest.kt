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

package me.ahoo.wow.api.query.gateway

import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.QueryExpression
import java.util.Collections

sealed interface QueryRequest {
    val target: QueryTarget
    val expression: QueryExpression
    val requestedScope: RequestedQueryScope
    val budget: QueryBudgetHint
}

sealed interface ResultQueryRequest<R : Any> : QueryRequest {
    val resultShape: QueryResultShape<R>
}

class SingleQueryRequest<R : Any>(
    override val target: QueryTarget,
    override val expression: QueryExpression = MatchAll,
    override val resultShape: QueryResultShape<R>,
    override val requestedScope: RequestedQueryScope = RequestedQueryScope(),
    override val budget: QueryBudgetHint = QueryBudgetHint(),
    sort: List<QuerySort> = emptyList()
) : ResultQueryRequest<R> {
    val sort: List<QuerySort> = immutableList(sort)

    override fun equals(other: Any?): Boolean = other is SingleQueryRequest<*> &&
        target == other.target && expression == other.expression && resultShape == other.resultShape &&
        requestedScope == other.requestedScope && budget == other.budget && sort == other.sort

    override fun hashCode(): Int = requestHashCode(target, expression, resultShape, requestedScope, budget, sort)

    override fun toString(): String =
        "SingleQueryRequest(target=$target, expression=$expression, resultShape=$resultShape, " +
            "requestedScope=$requestedScope, budget=$budget, sort=$sort)"
}

class ListQueryRequest<R : Any>(
    override val target: QueryTarget,
    override val expression: QueryExpression = MatchAll,
    override val resultShape: QueryResultShape<R>,
    override val requestedScope: RequestedQueryScope = RequestedQueryScope(),
    override val budget: QueryBudgetHint = QueryBudgetHint(),
    sort: List<QuerySort> = emptyList(),
    val limit: Int = 0
) : ResultQueryRequest<R> {
    val sort: List<QuerySort> = immutableList(sort)

    init {
        require(limit >= 0) { "limit cannot be negative." }
    }

    override fun equals(other: Any?): Boolean = other is ListQueryRequest<*> &&
        target == other.target && expression == other.expression && resultShape == other.resultShape &&
        requestedScope == other.requestedScope && budget == other.budget && sort == other.sort && limit == other.limit

    override fun hashCode(): Int = 31 * requestHashCode(
        target,
        expression,
        resultShape,
        requestedScope,
        budget,
        sort
    ) + limit

    override fun toString(): String =
        "ListQueryRequest(target=$target, expression=$expression, resultShape=$resultShape, " +
            "requestedScope=$requestedScope, budget=$budget, sort=$sort, limit=$limit)"
}

class PageQueryRequest<R : Any>(
    override val target: QueryTarget,
    override val expression: QueryExpression = MatchAll,
    override val resultShape: QueryResultShape<R>,
    override val requestedScope: RequestedQueryScope = RequestedQueryScope(),
    override val budget: QueryBudgetHint = QueryBudgetHint(),
    sort: List<QuerySort> = emptyList(),
    val page: QueryPageSpec = QueryPageSpec(index = 1, size = 10)
) : ResultQueryRequest<R> {
    val sort: List<QuerySort> = immutableList(sort)

    override fun equals(other: Any?): Boolean = other is PageQueryRequest<*> &&
        target == other.target && expression == other.expression && resultShape == other.resultShape &&
        requestedScope == other.requestedScope && budget == other.budget && sort == other.sort && page == other.page

    override fun hashCode(): Int = 31 * requestHashCode(
        target,
        expression,
        resultShape,
        requestedScope,
        budget,
        sort
    ) + page.hashCode()

    override fun toString(): String =
        "PageQueryRequest(target=$target, expression=$expression, resultShape=$resultShape, " +
            "requestedScope=$requestedScope, budget=$budget, sort=$sort, page=$page)"
}

data class CountQueryRequest(
    override val target: QueryTarget,
    override val expression: QueryExpression = MatchAll,
    override val requestedScope: RequestedQueryScope = RequestedQueryScope(),
    override val budget: QueryBudgetHint = QueryBudgetHint()
) : QueryRequest

private fun requestHashCode(vararg values: Any?): Int = values.fold(1) { result, value ->
    31 * result + (value?.hashCode() ?: 0)
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
