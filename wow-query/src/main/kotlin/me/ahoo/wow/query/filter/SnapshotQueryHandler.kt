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
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.IQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.filter.AbstractHandler
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.Handler
import me.ahoo.wow.filter.LogErrorHandler
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface SnapshotQueryHandler : Handler<SnapshotQueryContext<*, *>> {
    fun <S : Any> single(namedAggregate: NamedAggregate, condition: Condition): Mono<Snapshot<S>> {
        val context = SingleSnapshotQueryContext<S>(namedAggregate, condition, null)
        return handle(context).then(Mono.defer { context.result!! })
    }

    fun <S : Any> query(namedAggregate: NamedAggregate, query: IQuery): Flux<Snapshot<S>> {
        val context = QuerySnapshotQueryContext<S>(namedAggregate, query, null)
        return handle(context).thenMany(Flux.defer { context.result!! })
    }

    fun <S : Any> pagedQuery(namedAggregate: NamedAggregate, pagedQuery: IPagedQuery): Mono<PagedList<Snapshot<S>>> {
        val context = PagedSnapshotQueryContext<S>(namedAggregate, pagedQuery, null)
        return handle(context).then(Mono.defer { context.result!! })
    }

    fun count(namedAggregate: NamedAggregate, condition: Condition): Mono<Long> {
        val context = CountSnapshotQueryContext(namedAggregate, condition, null)
        return handle(context).then(Mono.defer { context.result!! })
    }
}

class DefaultSnapshotQueryHandler(
    chain: FilterChain<SnapshotQueryContext<*, *>>,
    errorHandler: ErrorHandler<SnapshotQueryContext<*, *>> = LogErrorHandler()
) : SnapshotQueryHandler, AbstractHandler<SnapshotQueryContext<*, *>>(
    chain,
    errorHandler,
)
