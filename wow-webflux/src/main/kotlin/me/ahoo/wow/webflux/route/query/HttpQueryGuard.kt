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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.query.filter.hasArithmeticExpression
import me.ahoo.wow.query.filter.isExpensive
import me.ahoo.wow.query.filter.isMatchAll
import me.ahoo.wow.query.filter.valueCount
import me.ahoo.wow.query.filter.walkFilterNodes
import me.ahoo.wow.query.filter.walkHavingNodes
import me.ahoo.wow.webflux.route.acceptsEventStream
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

class HttpQueryGuard(
    private val maxListSize: Int = 1000,
    private val defaultListSize: Int = DEFAULT_LIST_SIZE,
    private val maxPageSize: Int = 100,
    private val maxPageWindow: Long = 10_000,
    private val maxFilterNodes: Int = DEFAULT_MAX_FILTER_NODES,
    private val maxFilterValues: Int = 1000,
    private val allowExpensiveOperators: Boolean = true,
    private val idleTimeout: Duration = Duration.ofSeconds(10),
) {

    init {
        require(maxListSize >= 0) { "maxListSize must be greater than or equal to 0." }
        require(defaultListSize >= 0) { "defaultListSize must be greater than or equal to 0." }
        require(maxPageSize >= 0) { "maxPageSize must be greater than or equal to 0." }
        require(maxPageWindow >= 0) { "maxPageWindow must be greater than or equal to 0." }
        require(maxFilterNodes >= 0) { "maxFilterNodes must be greater than or equal to 0." }
        require(maxFilterValues >= 0) { "maxFilterValues must be greater than or equal to 0." }
        require(!idleTimeout.isNegative) { "idleTimeout must be greater than or equal to 0." }
    }

    /** Checks a single query against the HTTP limits before it reaches the gateway. */
    fun check(query: ISingleQuery, scope: FilterExpression = MatchAllFilter) {
        checkFilter(query.filter, scope, counting = false)
    }

    /** Checks a list query against the HTTP limits before it reaches the gateway. */
    fun check(query: IListQuery, scope: FilterExpression = MatchAllFilter) {
        validateList(query)
        checkFilter(query.filter, scope, counting = false)
    }

    /** Checks a paged query against the HTTP limits before it reaches the gateway. */
    fun check(query: IPagedQuery, scope: FilterExpression = MatchAllFilter) {
        validatePage(query)
        checkFilter(query.filter, scope, counting = true)
    }

    /** Checks a cursor query against the HTTP limits before it reaches the gateway. */
    fun check(query: ICursorQuery, scope: FilterExpression = MatchAllFilter) {
        validateCursor(query)
        checkFilter(query.filter, scope, counting = false)
    }

    /** Checks an aggregation query against the HTTP limits before it reaches the gateway. */
    fun check(query: AggregationQuery, scope: FilterExpression = MatchAllFilter) {
        validateAggregation(query, scope.asScopeFilters())
    }

    /** Checks the filter of a count query against the HTTP limits before it reaches the gateway. */
    fun checkCount(filter: FilterExpression, scope: FilterExpression = MatchAllFilter) {
        checkFilter(filter, scope, counting = true)
    }

    /**
     * Bounds the execution of a single-result query: applies the idle timeout and rejects a page that
     * exceeds [maxPageSize]. [result] runs on subscription, so checks it performs fail the publisher.
     */
    fun <T : Any> mono(result: () -> Mono<T>): Mono<T> {
        val source = Mono.defer(result).doOnNext { value ->
            val size = when (value) {
                is PagedList<*> -> value.list.size
                is CursorPage<*> -> value.list.size
                else -> return@doOnNext
            }
            require(maxPageSize == 0 || size <= maxPageSize) {
                "HTTP query returned [$size] rows, exceeding page limit [$maxPageSize]."
            }
        }
        return if (idleTimeout.isZero) source else source.timeout(idleTimeout)
    }

    /**
     * Bounds the execution of a streaming query: applies the idle timeout, rejects more than
     * [maxListSize] rows, and buffers the rows unless the client accepts an event stream, so a late
     * failure still produces an error response. [result] runs on subscription.
     */
    fun <T : Any> flux(request: ServerRequest, result: () -> Flux<T>): Flux<T> {
        val source = Flux.defer(result)
        val timed = if (idleTimeout.isZero) source else source.timeout(idleTimeout)
        val bounded = if (maxListSize == 0) {
            timed
        } else {
            timed.index().map { indexed ->
                require(indexed.t1 < maxListSize) { "HTTP query returned more than [$maxListSize] rows." }
                indexed.t2
            }
        }
        return if (request.acceptsEventStream()) {
            bounded
        } else {
            bounded.collectList().flatMapMany { Flux.fromIterable(it) }
        }
    }

    private fun FilterExpression.asScopeFilters(): List<FilterExpression> =
        if (this === MatchAllFilter) emptyList() else listOf(this)

    private fun checkFilter(filter: FilterExpression, scope: FilterExpression, counting: Boolean) {
        validateFilters(
            filters = listOf(filter) + scope.asScopeFilters(),
            rejectMatchAll = !allowExpensiveOperators && counting,
        )
    }

    private fun validateAggregation(query: AggregationQuery, scopeFilters: List<FilterExpression>) {
        validateResultSize(query.limit, "aggregation")
        val filterNodes = validateFilters(
            listOf(query.filter) +
                query.elements.map(AggregationElement::filter) +
                query.metrics.map(AggregationMetric::filter).filter { it !== MatchAllFilter } +
                scopeFilters,
            rejectMatchAll = false,
        )
        require(allowExpensiveOperators || query.elements.isEmpty()) {
            "HTTP aggregation elements are disabled because expensive operators are not allowed."
        }
        val metricAliases = query.metrics.mapTo(hashSetOf(), AggregationMetric::alias)
        require(allowExpensiveOperators || query.sort.none { it.field.path in metricAliases }) {
            "HTTP aggregation metric sorting is disabled because expensive operators are not allowed."
        }
        require(allowExpensiveOperators || query.metrics.none(AggregationMetric::hasArithmeticExpression)) {
            "HTTP aggregation arithmetic expressions are disabled because expensive operators are not allowed."
        }
        query.having?.let { validateHaving(it, filterNodes) }
    }

    private fun validateList(query: IListQuery) {
        validateResultSize(query.limit, "list")
    }

    /**
     * Rewrites an unbounded ([IListQuery.limit] == 0) request-body list query to the server-side
     * default list size, so clients following the published `limit` default are not rejected.
     * Negative limits are kept for [validateList] to reject. Disabled when either
     * [defaultListSize] or [maxListSize] is 0, matching the 0-disables convention: with list caps
     * off, 0 keeps its query-model meaning of unlimited.
     */
    fun applyListDefault(query: ListQuery): ListQuery {
        if (query.limit != 0 || defaultListSize == 0 || maxListSize == 0) {
            return query
        }
        return query.copy(limit = defaultListSize.coerceAtMost(maxListSize))
    }

    private fun validateResultSize(limit: Int, queryName: String) {
        val minimum = if (maxListSize == 0) 0 else 1
        require(limit >= minimum && (maxListSize == 0 || limit <= maxListSize)) {
            "HTTP $queryName query limit[$limit] must be between $minimum and $maxListSize."
        }
    }

    private fun validatePage(query: IPagedQuery) {
        val pagination = query.pagination
        require(pagination.index >= 1) { "HTTP page index[${pagination.index}] must be greater than or equal to 1." }
        require(pagination.size >= 1 && (maxPageSize == 0 || pagination.size <= maxPageSize)) {
            "HTTP page size[${pagination.size}] must be between 1 and $maxPageSize."
        }
        val window = pagination.index.toLong() * pagination.size.toLong()
        require(maxPageWindow == 0L || window <= maxPageWindow) {
            "HTTP page window[$window] must not exceed $maxPageWindow."
        }
        val offset = (pagination.index.toLong() - 1) * pagination.size
        require(offset <= Int.MAX_VALUE) {
            "HTTP page offset[$offset] must not exceed ${Int.MAX_VALUE}."
        }
    }

    private fun validateCursor(query: ICursorQuery) {
        require(query.size >= 1 && (maxPageSize == 0 || query.size <= maxPageSize)) {
            "HTTP cursor size[${query.size}] must be between 1 and $maxPageSize."
        }
    }

    private fun validateFilters(filters: List<FilterExpression>, rejectMatchAll: Boolean): Int {
        var nodes = 0
        filters.walkFilterNodes().forEach { current ->
            nodes++
            require(maxFilterNodes == 0 || nodes <= maxFilterNodes) {
                "HTTP query filter nodes[$nodes] must not exceed $maxFilterNodes."
            }
            validateFilterNode(current)
        }
        require(!rejectMatchAll || !filters.all { it.isMatchAll() }) {
            "HTTP counting query must not match all documents."
        }
        return nodes
    }

    private fun validateFilterNode(filter: FilterExpression) {
        require(allowExpensiveOperators || !filter.isExpensive()) {
            "HTTP query operator[${filter.operator}] is disabled because expensive operators are not allowed."
        }
        val valueCount = filter.valueCount()
        if (valueCount != null) {
            require(maxFilterValues == 0 || valueCount <= maxFilterValues) {
                "HTTP query filter values[$valueCount] must not exceed $maxFilterValues."
            }
        }
    }

    private fun validateHaving(having: HavingExpression, sharedNodes: Int) {
        var nodes = sharedNodes
        having.walkHavingNodes().forEach { current ->
            nodes++
            require(maxFilterNodes == 0 || nodes <= maxFilterNodes) {
                "HTTP filter and having nodes[$nodes] must not exceed $maxFilterNodes."
            }
            val valueCount = current.valueCount()
            if (valueCount > 0) {
                require(maxFilterValues == 0 || valueCount <= maxFilterValues) {
                    "HTTP having values[$valueCount] must not exceed $maxFilterValues."
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_FILTER_NODES: Int = 128
        const val DEFAULT_LIST_SIZE: Int = 100
    }
}
