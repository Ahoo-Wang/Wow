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

@file:Suppress("DEPRECATION", "NoWildcardImports", "WildcardImport")

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.query.*
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.webflux.route.acceptsEventStream
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.ArrayDeque

class HttpQueryGuard(
    private val maxListSize: Int = 1000,
    private val maxPageSize: Int = 100,
    private val maxPageWindow: Long = 10_000,
    private val maxFilterNodes: Int = DEFAULT_MAX_FILTER_NODES,
    private val maxFilterValues: Int = 1000,
    private val allowExpensiveOperators: Boolean = true,
    private val idleTimeout: Duration = Duration.ofSeconds(10),
) {

    init {
        require(maxListSize >= 0) { "maxListSize must be greater than or equal to 0." }
        require(maxPageSize >= 0) { "maxPageSize must be greater than or equal to 0." }
        require(maxPageWindow >= 0) { "maxPageWindow must be greater than or equal to 0." }
        require(maxFilterNodes >= 0) { "maxFilterNodes must be greater than or equal to 0." }
        require(maxFilterValues >= 0) { "maxFilterValues must be greater than or equal to 0." }
        require(!idleTimeout.isNegative) { "idleTimeout must be greater than or equal to 0." }
    }

    fun <T : Any> mono(
        queryType: QueryType,
        query: Any,
        scope: FilterExpression = MatchAllFilter,
        result: () -> Mono<T>,
    ): Mono<T> {
        val source = Mono.defer {
            validate(queryType, query, scope)
            result()
        }.doOnNext { value ->
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

    fun <T : Any> flux(
        queryType: QueryType,
        query: Any,
        request: ServerRequest,
        scope: FilterExpression = MatchAllFilter,
        result: () -> Flux<T>,
    ): Flux<T> {
        val source = Flux.defer {
            validate(queryType, query, scope)
            result()
        }
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
            bounded.collectList().flatMapMany {
                Flux.fromIterable(
                    it
                )
            }
        }
    }

    private fun validate(queryType: QueryType, query: Any, scope: FilterExpression) {
        val scopeFilters = if (scope === MatchAllFilter) emptyList() else listOf(scope)
        when (query) {
            is AggregationQuery -> {
                validateResultSize(query.limit, "aggregation")
                validateFilters(
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
                require(
                    allowExpensiveOperators || query.metrics.none { metric ->
                        metric.hasArithmeticExpression()
                    },
                ) {
                    "HTTP aggregation arithmetic expressions are disabled because expensive operators are not allowed."
                }
                return
            }
            is IListQuery -> validateList(query)
            is ICursorQuery -> validateCursor(query)
            is IPagedQuery -> validatePage(query)
        }
        val filter = when (query) {
            is FilterExpression -> query
            is FilterCapable<*> -> query.filter
            else -> return
        }
        validateFilters(
            filters = listOf(filter) + scopeFilters,
            rejectMatchAll = !allowExpensiveOperators && queryType in COUNTING_QUERY_TYPES,
        )
    }

    private fun validateList(query: IListQuery) {
        validateResultSize(query.limit, "list")
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

    private fun validateFilters(filters: List<FilterExpression>, rejectMatchAll: Boolean) {
        val pending = ArrayDeque<FilterExpression>()
        pending.addAll(filters)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            nodes++
            require(maxFilterNodes == 0 || nodes <= maxFilterNodes) {
                "HTTP query filter nodes[$nodes] must not exceed $maxFilterNodes."
            }
            validateFilterNode(current)
            when (current) {
                is AndFilter -> pending.addAll(current.operands)
                is OrFilter -> pending.addAll(current.operands)
                is NorFilter -> pending.addAll(current.operands)
                is ElementMatchFilter -> pending.add(current.predicate)
                else -> Unit
            }
        }
        require(!rejectMatchAll || !filters.all { it.isMatchAll() }) {
            "HTTP counting query must not match all documents."
        }
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

    /**
     * Gates every expression-bearing metric on non-field inputs. Bare constants are included on
     * purpose: this matches the pre-existing NUMERIC gate, where a constant-only metric is also
     * rejected as an expensive operator.
     */
    private fun AggregationMetric.hasArithmeticExpression(): Boolean = when (this) {
        is AggregationMetric.Numeric -> expression !is AggregationExpression.Field
        is AggregationMetric.DistinctCount -> expression !is AggregationExpression.Field
        is AggregationMetric.Percentile -> expression !is AggregationExpression.Field
        is AggregationMetric.Derived -> true
        is AggregationMetric.Count, is AggregationMetric.Any -> false
    }

    private fun FilterExpression.isExpensive(): Boolean =
        operator in EXPENSIVE_OPERATORS ||
            this is StartsWithFilter && (value.isEmpty() || stringComparison == StringComparison.CASE_INSENSITIVE)

    private fun FilterExpression.isMatchAll(): Boolean {
        return when (this) {
            MatchAllFilter -> true
            is DeletionFilter -> deletionState == DeletionState.ALL
            is AndFilter -> operands.all { it.isMatchAll() }
            is OrFilter -> operands.any { it.isMatchAll() }
            else -> false
        }
    }

    private fun FilterExpression.valueCount(): Int? = when (this) {
        is InFilter -> values.size
        is NotInFilter -> values.size
        is ContainsAllFilter -> values.size
        is IdsFilter -> values.size
        is AggregateIdsFilter -> values.size
        else -> null
    }

    companion object {
        const val DEFAULT_MAX_FILTER_NODES: Int = 128

        private val EXPENSIVE_OPERATORS = setOf(
            FilterOperator.NE,
            FilterOperator.NOT_IN,
            FilterOperator.NOR,
            FilterOperator.IS_NULL,
            FilterOperator.IS_NOT_NULL,
            FilterOperator.NOT_EXISTS,
            FilterOperator.IS_EMPTY,
            FilterOperator.IS_NOT_EMPTY_STRING,
            FilterOperator.CONTAINS,
            FilterOperator.ENDS_WITH,
        )
        private val COUNTING_QUERY_TYPES = setOf(QueryType.PAGED, QueryType.COUNT)
    }
}
