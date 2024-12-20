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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.AbstractHandler
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.filter.QueryHandler
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface SnapshotQueryHandler : QueryHandler<SnapshotQueryContext<*, *, *>> {
    fun <S : Any> single(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<MaterializedSnapshot<S>> {
        val context = SingleSnapshotQueryContext<MaterializedSnapshot<S>>(
            namedAggregate = namedAggregate,
            queryType = QueryType.SINGLE
        ).setQuery(singleQuery)
        return handle(context)
            .checkpoint("Single ${namedAggregate.toStringWithAlias()} [SnapshotQueryHandler]")
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }

    fun dynamicSingle(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<DynamicDocument> {
        val context = SingleSnapshotQueryContext<DynamicDocument>(
            namedAggregate = namedAggregate,
            queryType = QueryType.DYNAMIC_SINGLE
        ).setQuery(singleQuery)
        return handle(context)
            .checkpoint("DynamicSingle ${namedAggregate.toStringWithAlias()} [SnapshotQueryHandler]")
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }

    fun <S : Any> list(namedAggregate: NamedAggregate, query: IListQuery): Flux<MaterializedSnapshot<S>> {
        val context = ListSnapshotQueryContext<MaterializedSnapshot<S>>(
            namedAggregate = namedAggregate,
            queryType = QueryType.LIST
        ).setQuery(query)
        return handle(context)
            .checkpoint("List ${namedAggregate.toStringWithAlias()} [SnapshotQueryHandler]")
            .thenMany(
                Flux.defer {
                    context.getRequiredResult()
                }
            )
    }

    override fun dynamicList(namedAggregate: NamedAggregate, query: IListQuery): Flux<DynamicDocument> {
        val context = ListSnapshotQueryContext<DynamicDocument>(
            namedAggregate = namedAggregate,
            queryType = QueryType.DYNAMIC_LIST
        ).setQuery(query)
        return handle(context)
            .checkpoint("DynamicList ${namedAggregate.toStringWithAlias()} [SnapshotQueryHandler]")
            .thenMany(
                Flux.defer {
                    context.getRequiredResult()
                }
            )
    }

    fun <S : Any> paged(
        namedAggregate: NamedAggregate,
        pagedQuery: IPagedQuery
    ): Mono<PagedList<MaterializedSnapshot<S>>> {
        val context = PagedSnapshotQueryContext<MaterializedSnapshot<S>>(
            namedAggregate = namedAggregate,
            queryType = QueryType.PAGED
        ).setQuery(pagedQuery)
        return handle(context)
            .checkpoint("Paged ${namedAggregate.toStringWithAlias()} [SnapshotQueryHandler]")
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }

    fun dynamicPaged(
        namedAggregate: NamedAggregate,
        pagedQuery: IPagedQuery
    ): Mono<PagedList<DynamicDocument>> {
        val context = PagedSnapshotQueryContext<DynamicDocument>(
            namedAggregate = namedAggregate,
            queryType = QueryType.DYNAMIC_PAGED
        ).setQuery(pagedQuery)
        return handle(context)
            .checkpoint("DynamicPaged ${namedAggregate.toStringWithAlias()} [SnapshotQueryHandler]")
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }

    override fun count(namedAggregate: NamedAggregate, condition: Condition): Mono<Long> {
        val context = CountSnapshotQueryContext(namedAggregate).setQuery(condition)
        return handle(context)
            .checkpoint("Count ${namedAggregate.toStringWithAlias()} [SnapshotQueryHandler]")
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }
}

class DefaultSnapshotQueryHandler(
    chain: FilterChain<SnapshotQueryContext<*, *, *>>,
    errorHandler: ErrorHandler<SnapshotQueryContext<*, *, *>> = LogErrorHandler()
) : SnapshotQueryHandler, AbstractHandler<SnapshotQueryContext<*, *, *>>(
    chain,
    errorHandler,
)
