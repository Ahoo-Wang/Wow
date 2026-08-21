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

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ConditionCapable
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.Operator
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
    private val allowRaw: Boolean = false,
    private val allowExpensiveOperators: Boolean = false,
    private val idleTimeout: Duration = Duration.ofSeconds(10),
) : QueryFilter<QueryContext<*, *>> {

    init {
        require(maxListSize >= 0) { "maxListSize must be greater than or equal to 0." }
        require(maxPageSize >= 0) { "maxPageSize must be greater than or equal to 0." }
        require(maxPageWindow >= 0) { "maxPageWindow must be greater than or equal to 0." }
        require(maxConditionNodes >= 0) { "maxConditionNodes must be greater than or equal to 0." }
        require(!idleTimeout.isNegative) { "idleTimeout must be greater than or equal to 0." }
    }

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>,
    ): Mono<Void> = Mono.deferContextual { contextView ->
        val request = contextView.getRawRequest<ServerRequest>()
        if (request == null) {
            return@deferContextual next.filter(context)
        }
        validate(context.getQuery())
        next.filter(context).doOnSuccess {
            applyIdleTimeout(context, request)
        }
    }

    private fun validate(query: Any) {
        when (query) {
            is IListQuery -> validateList(query)
            is IPagedQuery -> validatePage(query)
        }
        val condition = when (query) {
            is Condition -> query
            is ConditionCapable<*> -> query.condition
            else -> return
        }
        validateCondition(condition)
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

    private fun validateCondition(condition: Condition) {
        val pending = ArrayDeque<Condition>()
        pending.add(condition)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            nodes++
            require(maxConditionNodes == 0 || nodes <= maxConditionNodes) {
                "HTTP query condition nodes[$nodes] must not exceed $maxConditionNodes."
            }
            require(allowRaw || current.operator != Operator.RAW) {
                "HTTP query operator[RAW] is not allowed."
            }
            require(allowExpensiveOperators || current.operator !in EXPENSIVE_OPERATORS) {
                "HTTP query operator[${current.operator}] is disabled because expensive operators are not allowed."
            }
            pending.addAll(current.children)
        }
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
                        it.timeout(Mono.delay(idleTimeout))
                    }
                }

            QueryType.PAGED, QueryType.DYNAMIC_PAGED ->
                context.asPagedQuery<Any>().rewriteResult { it.timeout(idleTimeout) }

            QueryType.COUNT -> context.asCountQuery().rewriteResult { it.timeout(idleTimeout) }
        }
    }

    private companion object {
        val EXPENSIVE_OPERATORS = setOf(Operator.CONTAINS, Operator.ENDS_WITH)
    }
}
