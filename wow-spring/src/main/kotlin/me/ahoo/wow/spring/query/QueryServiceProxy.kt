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
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.requiredQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal class SnapshotQueryServiceProxy<S : Any>(
    private val delegate: SnapshotQueryService<S>,
    gateway: SnapshotQueryGateway,
) : QueryServiceProxy<MaterializedSnapshot<S>>(
    delegate.namedAggregate,
    gateway.cast(),
),
    SnapshotQueryService<S> {
    override val name: String
        get() = delegate.name
}

internal class EventStreamQueryServiceProxy(
    private val delegate: EventStreamQueryService,
    gateway: EventStreamQueryGateway,
) : QueryServiceProxy<DomainEventStream>(delegate.namedAggregate, gateway),
    EventStreamQueryService,
    QueryModelSchemaProvider {
    override fun schema(): Mono<QueryModelSchema> =
        Mono.defer { delegate.requiredQueryModelSchemaProvider().schema() }

    override fun refresh(): Mono<QueryModelSchema> =
        Mono.defer { delegate.requiredQueryModelSchemaProvider().refresh() }
}

internal abstract class QueryServiceProxy<R : Any>(
    final override val namedAggregate: NamedAggregate,
    private val gateway: QueryGateway<R>,
) : QueryService<R> {
    override fun single(singleQuery: ISingleQuery): Mono<R> = gateway.single(namedAggregate, singleQuery)

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> =
        gateway.dynamicSingle(namedAggregate, singleQuery)

    override fun list(listQuery: IListQuery): Flux<R> = gateway.list(namedAggregate, listQuery)

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> =
        gateway.dynamicList(namedAggregate, listQuery)

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<R>> = gateway.paged(namedAggregate, pagedQuery)

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> =
        gateway.dynamicPaged(namedAggregate, pagedQuery)

    override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> = gateway.aggregate(namedAggregate, query)

    override fun count(filter: FilterExpression): Mono<Long> = gateway.count(namedAggregate, filter)
}

@Suppress("UNCHECKED_CAST")
private fun <S : Any> SnapshotQueryGateway.cast(): QueryGateway<MaterializedSnapshot<S>> =
    this as QueryGateway<MaterializedSnapshot<S>>
