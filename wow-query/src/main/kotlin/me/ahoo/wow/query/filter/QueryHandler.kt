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

interface QueryHandler<CONTEXT : QueryContext<*, *, *>, R : Any> : Handler<CONTEXT> {
    fun single(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<R>
    fun dynamicSingle(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<DynamicDocument>
    fun list(namedAggregate: NamedAggregate, listQuery: IListQuery): Flux<R>
    fun dynamicList(namedAggregate: NamedAggregate, query: IListQuery): Flux<DynamicDocument>
    fun paged(namedAggregate: NamedAggregate, pagedQuery: IPagedQuery): Mono<PagedList<R>>
    fun dynamicPaged(namedAggregate: NamedAggregate, pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>>
    fun count(namedAggregate: NamedAggregate, condition: Condition): Mono<Long>
}

@Suppress("UNCHECKED_CAST")
abstract class AbstractQueryHandler<CONTEXT : QueryContext<*, *, *>, R : Any>(
    private val chain: FilterChain<CONTEXT>,
    private val errorHandler: ErrorHandler<CONTEXT>
) : QueryHandler<CONTEXT, R> {
    override fun handle(context: CONTEXT): Mono<Void> {
        return chain.filter(context)
            .onErrorResume {
                if (context is ErrorAccessor) {
                    context.setError(it)
                }
                errorHandler.handle(context, it)
            }
    }

    override fun single(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<R> {
        val context = SingleQueryContext<R>(
            namedAggregate = namedAggregate,
            queryType = QueryType.SINGLE
        ).setQuery(singleQuery) as CONTEXT
        return handle(context)
            .then(
                Mono.defer {
                    context.getRequiredResult() as Mono<R>
                }
            )
    }

    override fun dynamicSingle(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<DynamicDocument> {
        val context = SingleQueryContext<DynamicDocument>(
            namedAggregate = namedAggregate,
            queryType = QueryType.DYNAMIC_SINGLE
        ).setQuery(singleQuery) as CONTEXT
        return handle(context)
            .then(
                Mono.defer {
                    context.getRequiredResult() as Mono<DynamicDocument>
                }
            )
    }

    override fun list(namedAggregate: NamedAggregate, query: IListQuery): Flux<R> {
        val context = ListQueryContext<R>(
            namedAggregate = namedAggregate,
            queryType = QueryType.LIST
        ).setQuery(query) as CONTEXT
        return handle(context)
            .thenMany(
                Flux.defer {
                    context.getRequiredResult() as Flux<R>
                }
            )
    }

    override fun dynamicList(namedAggregate: NamedAggregate, query: IListQuery): Flux<DynamicDocument> {
        val context = ListQueryContext<DynamicDocument>(
            namedAggregate = namedAggregate,
            queryType = QueryType.DYNAMIC_LIST
        ).setQuery(query) as CONTEXT
        return handle(context)
            .thenMany(
                Flux.defer {
                    context.getRequiredResult() as Flux<DynamicDocument>
                }
            )
    }

    override fun paged(
        namedAggregate: NamedAggregate,
        pagedQuery: IPagedQuery
    ): Mono<PagedList<R>> {
        val context = PagedQueryContext<R>(
            namedAggregate = namedAggregate,
            queryType = QueryType.PAGED
        ).setQuery(pagedQuery) as CONTEXT
        return handle(context)
            .then(
                Mono.defer {
                    context.getRequiredResult() as Mono<PagedList<R>>
                }
            )
    }

    override fun dynamicPaged(
        namedAggregate: NamedAggregate,
        pagedQuery: IPagedQuery
    ): Mono<PagedList<DynamicDocument>> {
        val context = PagedQueryContext<DynamicDocument>(
            namedAggregate = namedAggregate,
            queryType = QueryType.DYNAMIC_PAGED
        ).setQuery(pagedQuery) as CONTEXT
        return handle(context)
            .then(
                Mono.defer {
                    context.getRequiredResult() as Mono<PagedList<DynamicDocument>>
                }
            )
    }

    override fun count(namedAggregate: NamedAggregate, condition: Condition): Mono<Long> {
        val context = CountQueryContext(namedAggregate).setQuery(condition) as CONTEXT
        return handle(context)
            .then(
                Mono.defer {
                    context.getRequiredResult() as Mono<Long>
                }
            )
    }
}
