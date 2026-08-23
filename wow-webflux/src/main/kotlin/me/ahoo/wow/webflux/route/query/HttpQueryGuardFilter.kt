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

@file:Suppress("NoWildcardImports", "WildcardImport")

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
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
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
    private val maxConditionNodes: Int = 64,
    private val maxConditionValues: Int = 1000,
    private val allowExpensiveOperators: Boolean = false,
    private val idleTimeout: Duration = Duration.ofSeconds(10),
) : QueryFilter<QueryContext<*, *>> {

    init {
        require(maxListSize >= 0) { "maxListSize must be greater than or equal to 0." }
        require(maxPageSize >= 0) { "maxPageSize must be greater than or equal to 0." }
        require(maxPageWindow >= 0) { "maxPageWindow must be greater than or equal to 0." }
        require(maxConditionNodes >= 0) { "maxConditionNodes must be greater than or equal to 0." }
        require(maxConditionValues >= 0) { "maxConditionValues must be greater than or equal to 0." }
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
        validate(context.queryType, context.getQuery())
        val downstream = next.filter(context)
        val guardedDownstream = if (idleTimeout.isZero) downstream else downstream.timeout(idleTimeout)
        guardedDownstream.doOnSuccess {
            applyIdleTimeout(context, request)
        }
    }

    private fun validate(queryType: QueryType, query: Any) {
        when (query) {
            is IListQuery -> validateList(query)
            is IPagedQuery -> validatePage(query)
        }
        val filter = when (query) {
            is FilterExpression -> query
            is FilterCapable<*> -> query.filter
            else -> return
        }
        validateFilter(
            filter = filter,
            rejectMatchAll = !allowExpensiveOperators && queryType in COUNTING_QUERY_TYPES,
        )
    }

    private fun validateConditionNode(condition: Condition) {
        if (condition.operator == Operator.ELEM_MATCH) {
            require(condition.children.size == 1) {
                "HTTP ELEM_MATCH condition must contain exactly one child."
            }
        }
        require(allowExpensiveOperators || !condition.isExpensive()) {
            "HTTP query operator[${condition.operator}] is disabled because expensive operators are not allowed."
        }
        val values = condition.value
        if (condition.operator in LEGACY_COLLECTION_OPERATORS && values is Collection<*>) {
            require(maxConditionValues == 0 || values.size <= maxConditionValues) {
                "HTTP query condition values[${values.size}] must not exceed $maxConditionValues."
            }
        }
    }

    private fun Condition.isExpensive(): Boolean =
        operator in LEGACY_EXPENSIVE_OPERATORS ||
            operator == Operator.EXISTS && value == false ||
            operator == Operator.STARTS_WITH && ((value as? String).isNullOrEmpty() || ignoreCase() == true)

    private fun Condition.isMatchAll(): Boolean {
        val values = value
        return when (operator) {
            Operator.ALL -> true
            Operator.DELETED -> deletionState() == DeletionState.ALL
            Operator.NOT_IN -> values is Collection<*> && values.isEmpty()
            Operator.AND -> children.all { it.isMatchAll() }
            Operator.OR -> children.any { it.isMatchAll() }
            else -> false
        }
    }

    private fun validateList(query: IListQuery) {
        val minimum = if (maxListSize == 0) 0 else 1
        require(query.limit >= minimum && (maxListSize == 0 || query.limit <= maxListSize)) {
            "HTTP list query limit[${query.limit}] must be between $minimum and $maxListSize."
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

    private fun validateFilter(filter: FilterExpression, rejectMatchAll: Boolean) {
        val pending = ArrayDeque<Any>()
        pending.add(filter)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            nodes++
            require(maxConditionNodes == 0 || nodes <= maxConditionNodes) {
                "HTTP query condition nodes[$nodes] must not exceed $maxConditionNodes."
            }
            when (current) {
                is FilterExpression -> current.legacyConditionOrNull()?.let {
                    validateConditionNode(it)
                    pending.addAll(it.children)
                } ?: run {
                    validateFilterNode(current)
                    when (current) {
                        is AndFilter -> pending.addAll(current.operands)
                        is OrFilter -> pending.addAll(current.operands)
                        is NorFilter -> pending.addAll(current.operands)
                        is ElementMatchFilter -> pending.add(current.predicate)
                        else -> Unit
                    }
                }
                is Condition -> {
                    validateConditionNode(current)
                    pending.addAll(current.children)
                }
            }
        }
        require(!rejectMatchAll || !filter.isMatchAll()) {
            "HTTP counting query must not match all documents."
        }
    }

    private fun validateFilterNode(filter: FilterExpression) {
        require(allowExpensiveOperators || !filter.isExpensive()) {
            "HTTP query operator[${filter.operator}] is disabled because expensive operators are not allowed."
        }
        val valueCount = filter.valueCount()
        if (valueCount != null) {
            require(maxConditionValues == 0 || valueCount <= maxConditionValues) {
                "HTTP query condition values[$valueCount] must not exceed $maxConditionValues."
            }
        }
    }

    private fun FilterExpression.isExpensive(): Boolean =
        operator in EXPENSIVE_OPERATORS ||
            this is StartsWithFilter && (value.isEmpty() || stringComparison == StringComparison.CASE_INSENSITIVE)

    private fun FilterExpression.isMatchAll(): Boolean {
        legacyConditionOrNull()?.let { return it.isMatchAll() }
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
        val LEGACY_EXPENSIVE_OPERATORS = setOf(
            Operator.NE,
            Operator.NOT_IN,
            Operator.NOR,
            Operator.NULL,
            Operator.NOT_NULL,
            Operator.CONTAINS,
            Operator.ENDS_WITH,
        )
        val LEGACY_COLLECTION_OPERATORS = setOf(
            Operator.IN,
            Operator.NOT_IN,
            Operator.ALL_IN,
            Operator.IDS,
            Operator.AGGREGATE_IDS,
        )
        val COUNTING_QUERY_TYPES = setOf(QueryType.PAGED, QueryType.DYNAMIC_PAGED, QueryType.COUNT)
    }
}
