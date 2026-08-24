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

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.query.*
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.filter.Contexts.getRawRequest
import me.ahoo.wow.query.filter.Contexts.getUserQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.snapshot.filter.SnapshotAggregationQueryContext
import me.ahoo.wow.query.snapshot.filter.SnapshotAggregationQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotAggregationQueryFilterProvider
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.webflux.route.acceptsEventStream
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.ArrayDeque

@Order(ORDER_FIRST)
@FilterType(SnapshotQueryHandler::class, EventStreamQueryHandler::class)
class HttpQueryGuardFilter(
    private val maxListSize: Int = 1000,
    private val maxPageSize: Int = 100,
    private val maxPageWindow: Long = 10_000,
    private val maxFilterNodes: Int = 64,
    private val maxFilterValues: Int = 1000,
    private val allowExpensiveOperators: Boolean = true,
    private val idleTimeout: Duration = Duration.ofSeconds(10),
    private val maxAggregationElements: Int = 3,
    private val maxAggregationMetrics: Int = 32,
) : QueryFilter<QueryContext<*, *>>, SnapshotAggregationQueryFilterProvider {

    constructor(
        maxListSize: Int,
        maxPageSize: Int,
        maxPageWindow: Long,
        maxConditionNodes: Int,
        maxConditionValues: Int,
        allowExpensiveOperators: Boolean,
        idleTimeout: Duration,
    ) : this(
        maxListSize = maxListSize,
        maxPageSize = maxPageSize,
        maxPageWindow = maxPageWindow,
        maxFilterNodes = maxConditionNodes,
        maxFilterValues = maxConditionValues,
        allowExpensiveOperators = allowExpensiveOperators,
        idleTimeout = idleTimeout,
        maxAggregationElements = 3,
        maxAggregationMetrics = 32,
    )

    override fun createSnapshotAggregationQueryFilter(): SnapshotAggregationQueryFilter =
        HttpAggregationQueryGuardFilter(this)

    init {
        require(maxListSize >= 0) { "maxListSize must be greater than or equal to 0." }
        require(maxPageSize >= 0) { "maxPageSize must be greater than or equal to 0." }
        require(maxPageWindow >= 0) { "maxPageWindow must be greater than or equal to 0." }
        require(maxFilterNodes >= 0) { "maxFilterNodes must be greater than or equal to 0." }
        require(maxFilterValues >= 0) { "maxFilterValues must be greater than or equal to 0." }
        require(maxAggregationElements in 0..AggregationQuery.MAX_ELEMENTS) {
            "maxAggregationElements must be between 0 and ${AggregationQuery.MAX_ELEMENTS}."
        }
        require(maxAggregationMetrics in 0..AggregationQuery.MAX_METRICS) {
            "maxAggregationMetrics must be between 0 and ${AggregationQuery.MAX_METRICS}."
        }
        require(!idleTimeout.isNegative) { "idleTimeout must be greater than or equal to 0." }
    }

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>,
    ): Mono<Void> = Mono.deferContextual { contextView ->
        val request = contextView.getRawRequest<Any>() as? ServerRequest
        if (request == null) {
            return@deferContextual next.filter(context)
        }
        validate(context.getQuery(), context.queryType in COUNTING_QUERY_TYPES)
        val downstream = next.filter(context)
        val guardedDownstream = if (idleTimeout.isZero) downstream else downstream.timeout(idleTimeout)
        guardedDownstream.doOnSuccess {
            applyIdleTimeout(context, request)
        }
    }

    internal fun filterAggregation(
        context: SnapshotAggregationQueryContext,
        next: FilterChain<SnapshotAggregationQueryContext>,
    ): Mono<Void> = Mono.deferContextual { contextView ->
        val request = contextView.getRawRequest<Any>() as? ServerRequest
        if (request == null) return@deferContextual next.filter(context)

        val userQuery = contextView.getUserQuery<AggregationQuery>() ?: context.query
        validateAggregation(userQuery)
        validateFilters(
            filters = listOf(userQuery.filter) + userQuery.elements.map(AggregationElement::filter),
            rejectMatchAll = context.query.filter,
        )
        val downstream = next.filter(context)
        val guardedDownstream = if (idleTimeout.isZero) downstream else downstream.timeout(idleTimeout)
        guardedDownstream.doOnSuccess { applyAggregationIdleTimeout(context, request) }
    }

    private fun validate(query: Any, rejectMatchAll: Boolean) {
        when (query) {
            is IListQuery -> validateList(query)
            is IPagedQuery -> validatePage(query)
        }
        val filter = when (query) {
            is FilterExpression -> query
            is FilterCapable<*> -> query.filter
            else -> return
        }
        validateFilters(listOf(filter), filter.takeIf { rejectMatchAll })
    }

    private fun validateAggregation(query: AggregationQuery) {
        if (query.groupBy.isNotEmpty()) validateResultSize(query.limit, "aggregation")
        require(maxAggregationElements == 0 || query.elements.size <= maxAggregationElements) {
            "HTTP aggregation elements[${query.elements.size}] must not exceed $maxAggregationElements."
        }
        require(maxAggregationMetrics == 0 || query.metrics.size <= maxAggregationMetrics) {
            "HTTP aggregation metrics[${query.metrics.size}] must not exceed $maxAggregationMetrics."
        }
        require(allowExpensiveOperators || query.elements.isEmpty()) {
            "HTTP aggregation elements are disabled because expensive operators are not allowed."
        }
        val metricAliases = query.metrics.mapTo(hashSetOf()) { it.alias }
        require(allowExpensiveOperators || query.sort.none { it.field in metricAliases }) {
            "HTTP aggregation metric sort is disabled because expensive operators are not allowed."
        }
    }

    private fun validateList(query: IListQuery) {
        val minimum = if (maxListSize == 0) 0 else 1
        require(query.limit >= minimum && (maxListSize == 0 || query.limit <= maxListSize)) {
            "HTTP list query limit[${query.limit}] must be between $minimum and $maxListSize."
        }
    }

    private fun validateResultSize(limit: Int, queryName: String) {
        val maximum = if (maxListSize == 0) AggregationQuery.MAX_LIMIT else maxListSize
        require(limit in 1..maximum) {
            "HTTP $queryName query limit[$limit] must be between 1 and $maximum."
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

    @Suppress("CyclomaticComplexMethod")
    private fun validateFilters(
        filters: List<FilterExpression>,
        rejectMatchAll: FilterExpression?,
    ) {
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
        require(allowExpensiveOperators || rejectMatchAll == null || !rejectMatchAll.isMatchAll()) {
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

    private fun applyIdleTimeout(context: QueryContext<*, *>, request: ServerRequest) {
        if (idleTimeout.isZero) return
        when (context.queryType) {
            QueryType.SINGLE, QueryType.DYNAMIC_SINGLE ->
                context.asSingleQuery<Any>().rewriteResult { it.timeout(idleTimeout) }

            QueryType.LIST, QueryType.DYNAMIC_LIST ->
                context.asListQuery<Any>().rewriteResult {
                    if (request.acceptsEventStream()) {
                        it.timeout(idleTimeout)
                    } else {
                        it.timeout(idleTimeout)
                            .collectList()
                            .flatMapMany(Flux<Any>::fromIterable)
                    }
                }

            QueryType.PAGED, QueryType.DYNAMIC_PAGED ->
                context.asPagedQuery<Any>().rewriteResult { it.timeout(idleTimeout) }

            QueryType.COUNT -> context.asCountQuery().rewriteResult { it.timeout(idleTimeout) }
        }
    }

    private fun applyAggregationIdleTimeout(
        context: SnapshotAggregationQueryContext,
        request: ServerRequest,
    ) {
        if (idleTimeout.isZero) return
        context.rewriteResult {
            if (request.acceptsEventStream()) {
                it.timeout(idleTimeout)
            } else {
                it.timeout(idleTimeout).collectList().flatMapMany(Flux<DynamicDocument>::fromIterable)
            }
        }
    }

    private companion object {
        val EXPENSIVE_OPERATORS = setOf(
            FilterOperator.NE,
            FilterOperator.NOT_IN,
            FilterOperator.NOR,
            FilterOperator.IS_NULL,
            FilterOperator.IS_NOT_NULL,
            FilterOperator.NOT_EXISTS,
            FilterOperator.IS_EMPTY,
            FilterOperator.CONTAINS,
            FilterOperator.ENDS_WITH,
        )
        val COUNTING_QUERY_TYPES = setOf(QueryType.PAGED, QueryType.DYNAMIC_PAGED, QueryType.COUNT)
    }
}

@Order(ORDER_FIRST)
class HttpAggregationQueryGuardFilter(
    private val delegate: HttpQueryGuardFilter,
) : SnapshotAggregationQueryFilter {
    override fun filter(
        context: SnapshotAggregationQueryContext,
        next: FilterChain<SnapshotAggregationQueryContext>,
    ): Mono<Void> = delegate.filterAggregation(context, next)
}
