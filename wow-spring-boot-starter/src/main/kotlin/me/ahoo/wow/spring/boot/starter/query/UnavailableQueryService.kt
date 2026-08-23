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

package me.ahoo.wow.spring.boot.starter.query

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
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal object UnavailableSnapshotQueryServiceFactory : SnapshotQueryServiceFactory {
    override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> =
        UnavailableSnapshotQueryService(namedAggregate.materialize())
}

internal object UnavailableEventStreamQueryServiceFactory : EventStreamQueryServiceFactory {
    override fun create(namedAggregate: NamedAggregate): EventStreamQueryService =
        UnavailableEventStreamQueryService(namedAggregate.materialize())
}

private class UnavailableSnapshotQueryService<S : Any>(namedAggregate: NamedAggregate) :
    UnavailableQueryService<MaterializedSnapshot<S>>(namedAggregate),
    SnapshotQueryService<S> {
    override val name: String = "unavailable"

    override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> = unavailableFlux()
}

private class UnavailableEventStreamQueryService(namedAggregate: NamedAggregate) :
    UnavailableQueryService<DomainEventStream>(namedAggregate),
    EventStreamQueryService

private abstract class UnavailableQueryService<R : Any>(
    final override val namedAggregate: NamedAggregate,
) : QueryService<R> {
    override fun single(singleQuery: ISingleQuery): Mono<R> = unavailableMono()

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> = unavailableMono()

    override fun list(listQuery: IListQuery): Flux<R> = unavailableFlux()

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> = unavailableFlux()

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<R>> = unavailableMono()

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> = unavailableMono()

    override fun count(filter: FilterExpression): Mono<Long> = unavailableMono()

    private fun <T : Any> unavailableMono(): Mono<T> = Mono.error(unavailable())

    protected fun <T : Any> unavailableFlux(): Flux<T> = Flux.error(unavailable())

    private fun unavailable(): WowException = WowException(
        ErrorCodes.INTERNAL_SERVER_ERROR,
        "No query backend is configured for aggregate[$namedAggregate].",
    )
}
