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
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.event.AbstractEventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

internal object UnavailableSnapshotQueryBackendFactory : AbstractSnapshotQueryBackendFactory() {
    override fun createBinding(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> =
        QueryBackendBinding(
            backend = UnavailableSnapshotQueryBackend(namedAggregate),
            schemaProvider = UnavailableQueryModelSchemaProvider(namedAggregate),
        )
}

internal object UnavailableEventStreamQueryBackendFactory : AbstractEventStreamQueryBackendFactory() {
    override fun createBinding(namedAggregate: NamedAggregate): QueryBackendBinding<EventStreamQueryBackend> =
        QueryBackendBinding(
            backend = UnavailableEventStreamQueryBackend(namedAggregate),
            schemaProvider = UnavailableQueryModelSchemaProvider(namedAggregate),
        )
}

private class UnavailableSnapshotQueryBackend(namedAggregate: NamedAggregate) :
    UnavailableQueryBackend(namedAggregate),
    SnapshotQueryBackend {
    override val name: String = "unavailable"
}

private class UnavailableEventStreamQueryBackend(namedAggregate: NamedAggregate) :
    UnavailableQueryBackend(namedAggregate),
    EventStreamQueryBackend

private abstract class UnavailableQueryBackend(
    final override val namedAggregate: NamedAggregate,
) : QueryBackend {
    override fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode> = unavailableMono()
    override fun list(query: ResolvedQuery<IListQuery>): Flux<ObjectNode> = unavailableFlux()
    override fun paged(query: ResolvedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>> = unavailableMono()
    override fun cursor(query: ResolvedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>> = unavailableMono()
    override fun count(query: ResolvedQuery<FilterExpression>): Mono<Long> = unavailableMono()
    override fun aggregate(query: ResolvedQuery<AggregationQuery>): Flux<ObjectNode> = unavailableFlux()

    private fun <T : Any> unavailableMono(): Mono<T> = Mono.error(unavailable())
    private fun <T : Any> unavailableFlux(): Flux<T> = Flux.error(unavailable())
    private fun unavailable(): WowException = WowException(
        ErrorCodes.INTERNAL_SERVER_ERROR,
        "No query backend is configured for aggregate[$namedAggregate].",
    )
}

private class UnavailableQueryModelSchemaProvider(
    private val namedAggregate: NamedAggregate,
) : QueryModelSchemaProvider {
    override fun schema(): Mono<QueryModelSchema> = Mono.error(schemaUnavailable())
    override fun refresh(): Mono<QueryModelSchema> = schema()

    private fun schemaUnavailable(): QuerySchemaUnavailableException = QuerySchemaUnavailableException(
        "No query backend is configured for aggregate[$namedAggregate].",
    )
}
