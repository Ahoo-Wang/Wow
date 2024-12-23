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

package me.ahoo.wow.query.filter

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.ErrorAccessor
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.Handler
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface QueryHandler<R : Any> : Handler<QueryContext<*, *>> {
    fun single(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<R>
    fun dynamicSingle(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<DynamicDocument>
    fun list(namedAggregate: NamedAggregate, listQuery: IListQuery): Flux<R>
    fun dynamicList(namedAggregate: NamedAggregate, query: IListQuery): Flux<DynamicDocument>
    fun paged(namedAggregate: NamedAggregate, pagedQuery: IPagedQuery): Mono<PagedList<R>>
    fun dynamicPaged(namedAggregate: NamedAggregate, pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>>
    fun count(namedAggregate: NamedAggregate, condition: Condition): Mono<Long>
}

@Suppress("UNCHECKED_CAST")
abstract class AbstractQueryHandler<R : Any>(
    private val chain: FilterChain<QueryContext<*, *>>,
    private val errorHandler: ErrorHandler<QueryContext<*, *>>
) : QueryHandler<R> {
    override fun handle(context: QueryContext<*, *>): Mono<Void> {
        return chain.filter(context)
            .onErrorResume {
                if (context is ErrorAccessor) {
                    context.setError(it)
                }
                errorHandler.handle(context, it)
            }
    }

    override fun single(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<R> {
        val context = DefaultQueryContext<ISingleQuery, Mono<R>>(
            namedAggregate = namedAggregate,
            queryType = QueryType.SINGLE
        ).setQuery(singleQuery)
        return handle(context)
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }

    override fun dynamicSingle(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<DynamicDocument> {
        val context = DefaultQueryContext<ISingleQuery, Mono<DynamicDocument>>(
            namedAggregate = namedAggregate,
            queryType = QueryType.DYNAMIC_SINGLE
        ).setQuery(singleQuery)
        return handle(context)
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }

    override fun list(namedAggregate: NamedAggregate, query: IListQuery): Flux<R> {
        val context = DefaultQueryContext<IListQuery, Flux<R>>(
            namedAggregate = namedAggregate,
            queryType = QueryType.LIST
        ).setQuery(query)
        return handle(context)
            .thenMany(
                Flux.defer {
                    context.getRequiredResult()
                }
            )
    }

    override fun dynamicList(namedAggregate: NamedAggregate, query: IListQuery): Flux<DynamicDocument> {
        val context = DefaultQueryContext<IListQuery, Flux<DynamicDocument>>(
            namedAggregate = namedAggregate,
            queryType = QueryType.DYNAMIC_LIST
        ).setQuery(query)
        return handle(context)
            .thenMany(
                Flux.defer {
                    context.getRequiredResult()
                }
            )
    }

    override fun paged(
        namedAggregate: NamedAggregate,
        pagedQuery: IPagedQuery
    ): Mono<PagedList<R>> {
        val context = DefaultQueryContext<IPagedQuery, Mono<PagedList<R>>>(
            namedAggregate = namedAggregate,
            queryType = QueryType.PAGED
        ).setQuery(pagedQuery)
        return handle(context)
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }

    override fun dynamicPaged(
        namedAggregate: NamedAggregate,
        pagedQuery: IPagedQuery
    ): Mono<PagedList<DynamicDocument>> {
        val context = DefaultQueryContext<IPagedQuery, Mono<PagedList<DynamicDocument>>>(
            namedAggregate = namedAggregate,
            queryType = QueryType.DYNAMIC_PAGED
        ).setQuery(pagedQuery)
        return handle(context)
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }

    override fun count(namedAggregate: NamedAggregate, condition: Condition): Mono<Long> {
        val context = DefaultQueryContext<Condition, Mono<Long>>(
            namedAggregate = namedAggregate,
            queryType = QueryType.COUNT
        ).setQuery(condition)
        return handle(context)
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }
}
