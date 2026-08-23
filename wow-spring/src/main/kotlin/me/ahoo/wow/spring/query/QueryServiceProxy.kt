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

package me.ahoo.wow.spring.query

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.filter.QueryHandler
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal class SnapshotQueryServiceProxy<S : Any>(
    private val delegate: SnapshotQueryService<S>,
    private val snapshotQueryHandler: SnapshotQueryHandler,
) : QueryServiceProxy<MaterializedSnapshot<S>>(
    delegate.namedAggregate,
    snapshotQueryHandler.cast(),
),
    SnapshotQueryService<S> {
    override val name: String
        get() = delegate.name

    override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> =
        snapshotQueryHandler.aggregate(namedAggregate, query)
}

internal class EventStreamQueryServiceProxy(
    delegate: EventStreamQueryService,
    handler: EventStreamQueryHandler,
) : QueryServiceProxy<DomainEventStream>(delegate.namedAggregate, handler),
    EventStreamQueryService

internal abstract class QueryServiceProxy<R : Any>(
    final override val namedAggregate: NamedAggregate,
    private val handler: QueryHandler<R>,
) : QueryService<R> {
    override fun single(singleQuery: ISingleQuery): Mono<R> = handler.single(namedAggregate, singleQuery)

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> =
        handler.dynamicSingle(namedAggregate, singleQuery)

    override fun list(listQuery: IListQuery): Flux<R> = handler.list(namedAggregate, listQuery)

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> =
        handler.dynamicList(namedAggregate, listQuery)

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<R>> = handler.paged(namedAggregate, pagedQuery)

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> =
        handler.dynamicPaged(namedAggregate, pagedQuery)

    override fun count(filter: FilterExpression): Mono<Long> = handler.count(namedAggregate, filter)
}

@Suppress("UNCHECKED_CAST")
private fun <S : Any> SnapshotQueryHandler.cast(): QueryHandler<MaterializedSnapshot<S>> =
    this as QueryHandler<MaterializedSnapshot<S>>
