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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.ErrorAccessor
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.filter.DefaultQueryContext
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface QueryGateway<R : Any> {
    fun single(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<R>
    fun dynamicSingle(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<DynamicDocument>
    fun list(namedAggregate: NamedAggregate, listQuery: IListQuery): Flux<R>
    fun dynamicList(namedAggregate: NamedAggregate, listQuery: IListQuery): Flux<DynamicDocument>
    fun paged(namedAggregate: NamedAggregate, pagedQuery: IPagedQuery): Mono<PagedList<R>>
    fun dynamicPaged(namedAggregate: NamedAggregate, pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>>
    fun cursor(namedAggregate: NamedAggregate, query: ICursorQuery): Mono<CursorPage<R>> =
        Mono.error(UnsupportedOperationException("Cursor query is not supported."))

    fun dynamicCursor(namedAggregate: NamedAggregate, query: ICursorQuery): Mono<CursorPage<DynamicDocument>> =
        Mono.error(UnsupportedOperationException("Cursor query is not supported."))
    fun aggregate(namedAggregate: NamedAggregate, query: AggregationQuery): Flux<DynamicDocument>
    fun count(namedAggregate: NamedAggregate, filter: FilterExpression): Mono<Long>
}

abstract class AbstractQueryGateway<R : Any>(
    private val chain: FilterChain<QueryContext<*, *>>,
    private val errorHandler: ErrorHandler<QueryContext<*, *>>,
) : QueryGateway<R> {
    private fun execute(context: QueryContext<*, *>): Mono<Void> =
        chain.filter(context).onErrorResume { error ->
            if (context is ErrorAccessor) {
                context.setError(error)
            }
            errorHandler.handle(context, error)
        }

    private fun <Q : Any, T : Any> mono(
        namedAggregate: NamedAggregate,
        queryType: QueryType,
        query: Q,
    ): Mono<T> = Mono.defer {
        val context = DefaultQueryContext<Q, Mono<T>>(queryType, namedAggregate).setQuery(query)
        execute(context).then(Mono.defer { context.getRequiredResult() })
    }

    private fun <Q : Any, T : Any> flux(
        namedAggregate: NamedAggregate,
        queryType: QueryType,
        query: Q,
    ): Flux<T> = Flux.defer {
        val context = DefaultQueryContext<Q, Flux<T>>(queryType, namedAggregate).setQuery(query)
        execute(context).thenMany(Flux.defer { context.getRequiredResult() })
    }

    override fun single(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<R> =
        mono(namedAggregate, QueryType.SINGLE, singleQuery)

    override fun dynamicSingle(
        namedAggregate: NamedAggregate,
        singleQuery: ISingleQuery,
    ): Mono<DynamicDocument> = mono(namedAggregate, QueryType.DYNAMIC_SINGLE, singleQuery)

    override fun list(namedAggregate: NamedAggregate, listQuery: IListQuery): Flux<R> =
        flux(namedAggregate, QueryType.LIST, listQuery)

    override fun dynamicList(
        namedAggregate: NamedAggregate,
        listQuery: IListQuery,
    ): Flux<DynamicDocument> = flux(namedAggregate, QueryType.DYNAMIC_LIST, listQuery)

    override fun paged(
        namedAggregate: NamedAggregate,
        pagedQuery: IPagedQuery,
    ): Mono<PagedList<R>> = mono(namedAggregate, QueryType.PAGED, pagedQuery)

    override fun dynamicPaged(
        namedAggregate: NamedAggregate,
        pagedQuery: IPagedQuery,
    ): Mono<PagedList<DynamicDocument>> = mono(namedAggregate, QueryType.DYNAMIC_PAGED, pagedQuery)

    override fun cursor(namedAggregate: NamedAggregate, query: ICursorQuery): Mono<CursorPage<R>> =
        mono(namedAggregate, QueryType.CURSOR, query)

    override fun dynamicCursor(
        namedAggregate: NamedAggregate,
        query: ICursorQuery,
    ): Mono<CursorPage<DynamicDocument>> = mono(namedAggregate, QueryType.DYNAMIC_CURSOR, query)

    override fun aggregate(
        namedAggregate: NamedAggregate,
        query: AggregationQuery,
    ): Flux<DynamicDocument> = flux(namedAggregate, QueryType.AGGREGATION, query)

    override fun count(namedAggregate: NamedAggregate, filter: FilterExpression): Mono<Long> =
        mono(namedAggregate, QueryType.COUNT, filter)
}
