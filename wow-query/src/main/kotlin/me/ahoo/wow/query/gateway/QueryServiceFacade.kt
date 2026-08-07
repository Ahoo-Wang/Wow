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

@file:OptIn(ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.gateway

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.event.AbstractEventStreamQueryServiceFactory
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.convert
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@ExperimentalQueryGatewayApi
data class QueryCallResolutionRequest(
    val target: QueryTarget,
    val queryType: QueryType,
)

@ExperimentalQueryGatewayApi
fun interface QueryCallResolver {
    /** Called once for each subscription made through a compatibility QueryService facade. */
    fun resolve(request: QueryCallResolutionRequest): Mono<QueryCall>
}

@ExperimentalQueryGatewayApi
class GatewaySnapshotQueryServiceFactory(
    private val gateway: QueryGateway,
    private val callResolver: QueryCallResolver,
) : AbstractSnapshotQueryServiceFactory() {
    override fun createQueryService(namedAggregate: NamedAggregate): SnapshotQueryService<*> =
        GatewaySnapshotQueryService(namedAggregate.materialize(), gateway, callResolver)
}

@ExperimentalQueryGatewayApi
class GatewayEventStreamQueryServiceFactory(
    private val gateway: QueryGateway,
    private val callResolver: QueryCallResolver,
) : AbstractEventStreamQueryServiceFactory() {
    override fun createQueryService(namedAggregate: NamedAggregate): EventStreamQueryService =
        GatewayEventStreamQueryService(namedAggregate.materialize(), gateway, callResolver)
}

private class GatewaySnapshotQueryService(
    override val namedAggregate: NamedAggregate,
    private val gateway: QueryGateway,
    private val callResolver: QueryCallResolver,
) : SnapshotQueryService<Any> {
    override val name: String = GATEWAY_QUERY_SERVICE_NAME

    override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<Any>> =
        callMono(QueryType.SINGLE) { call -> gateway.single(call, singleQuery, MATERIALIZED_SNAPSHOT_TYPE) }
            .map(MaterializedSnapshot<*>::eraseStateType)

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> =
        callMono(QueryType.DYNAMIC_SINGLE) { call -> gateway.single(call, singleQuery) }

    override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<Any>> =
        callFlux(QueryType.LIST) { call -> gateway.stream(call, listQuery, MATERIALIZED_SNAPSHOT_TYPE) }
            .map(MaterializedSnapshot<*>::eraseStateType)

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> =
        callFlux(QueryType.DYNAMIC_LIST) { call -> gateway.stream(call, listQuery) }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<Any>>> =
        callMono(QueryType.PAGED) { call -> gateway.page(call, pagedQuery, MATERIALIZED_SNAPSHOT_TYPE) }
            .map { page ->
                PagedList(page.total, page.list.map(MaterializedSnapshot<*>::eraseStateType))
            }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> =
        callMono(QueryType.DYNAMIC_PAGED) { call -> gateway.page(call, pagedQuery) }

    override fun count(condition: Condition): Mono<Long> =
        callMono(QueryType.COUNT) { call -> gateway.count(call, condition) }

    private fun <R : Any> callMono(queryType: QueryType, source: (QueryCall) -> Mono<R>): Mono<R> =
        resolveCall(callResolver, target(), queryType).flatMap(source)

    private fun <R : Any> callFlux(queryType: QueryType, source: (QueryCall) -> Flux<R>): Flux<R> =
        resolveCall(callResolver, target(), queryType).flatMapMany(source)

    private fun target(): QueryTarget = QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT)
}

private class GatewayEventStreamQueryService(
    override val namedAggregate: NamedAggregate,
    private val gateway: QueryGateway,
    private val callResolver: QueryCallResolver,
) : EventStreamQueryService {
    override fun single(singleQuery: ISingleQuery): Mono<DomainEventStream> =
        callMono(QueryType.SINGLE) { call -> gateway.single(call, singleQuery, DomainEventStream::class.java) }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> =
        callMono(QueryType.DYNAMIC_SINGLE) { call -> gateway.single(call, singleQuery) }

    override fun list(listQuery: IListQuery): Flux<DomainEventStream> =
        callFlux(QueryType.LIST) { call -> gateway.stream(call, listQuery, DomainEventStream::class.java) }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> =
        callFlux(QueryType.DYNAMIC_LIST) { call -> gateway.stream(call, listQuery) }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<DomainEventStream>> =
        callMono(QueryType.PAGED) { call -> gateway.page(call, pagedQuery, DomainEventStream::class.java) }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> =
        callMono(QueryType.DYNAMIC_PAGED) { call -> gateway.page(call, pagedQuery) }

    override fun count(condition: Condition): Mono<Long> =
        callMono(QueryType.COUNT) { call -> gateway.count(call, condition) }

    private fun <R : Any> callMono(queryType: QueryType, source: (QueryCall) -> Mono<R>): Mono<R> =
        resolveCall(callResolver, target(), queryType).flatMap(source)

    private fun <R : Any> callFlux(queryType: QueryType, source: (QueryCall) -> Flux<R>): Flux<R> =
        resolveCall(callResolver, target(), queryType).flatMapMany(source)

    private fun target(): QueryTarget = QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM)
}

private fun resolveCall(
    resolver: QueryCallResolver,
    target: QueryTarget,
    queryType: QueryType,
): Mono<QueryCall> = Mono.defer {
    resolver.resolve(QueryCallResolutionRequest(target, queryType))
}.onErrorMap { error ->
    if (error is QueryExecutionException) {
        error
    } else {
        queryCallError("QUERY_CALL_RESOLUTION_FAILED", error)
    }
}.switchIfEmpty(Mono.error(queryCallError("QUERY_CALL_REQUIRED")))
    .map { call ->
        if (call.target != target) {
            throw queryCallError("QUERY_CALL_TARGET_MISMATCH")
        }
        call
    }

@ExperimentalQueryGatewayApi
object QueryResultMaterializers {
    fun snapshot(target: QueryTarget, stateType: Class<*>): QueryResultMaterializer<MaterializedSnapshot<*>> {
        require(target.documentKind == QueryDocumentKind.SNAPSHOT) {
            "Snapshot materializer requires a SNAPSHOT target."
        }
        val snapshotType = JsonSerializer.typeFactory.constructParametricType(
            MaterializedSnapshot::class.java,
            stateType,
        )
        return QueryResultMaterializer(target, MATERIALIZED_SNAPSHOT_TYPE) { identity, document ->
            document.convert<MaterializedSnapshot<*>>(snapshotType).also { snapshot ->
                require(snapshot.aggregateId == identity) {
                    "Materialized snapshot identity does not match the backend record identity."
                }
                require(stateType.isInstance(snapshot.state)) {
                    "Materialized snapshot state does not match the registered state type."
                }
            }
        }
    }

    fun eventStream(target: QueryTarget): QueryResultMaterializer<DomainEventStream> {
        require(target.documentKind == QueryDocumentKind.EVENT_STREAM) {
            "Event-stream materializer requires an EVENT_STREAM target."
        }
        return QueryResultMaterializer(target, DomainEventStream::class.java) { identity, document ->
            document.convert(DomainEventStream::class.java).also { eventStream ->
                require(eventStream.id == identity) {
                    "Materialized event-stream identity does not match the backend record identity."
                }
            }
        }
    }
}

private fun queryCallError(code: String, cause: Throwable? = null): QueryExecutionException =
    QueryExecutionException(QueryErrorCategory.ACCESS_DENIED, "$.executionContext.call", code, cause)

private fun MaterializedSnapshot<*>.eraseStateType(): MaterializedSnapshot<Any> = MaterializedSnapshot(
    contextName = contextName,
    aggregateName = aggregateName,
    tenantId = tenantId,
    ownerId = ownerId,
    spaceId = spaceId,
    aggregateId = aggregateId,
    version = version,
    eventId = eventId,
    firstOperator = firstOperator,
    operator = operator,
    firstEventTime = firstEventTime,
    eventTime = eventTime,
    state = state,
    snapshotTime = snapshotTime,
    tags = tags,
    deleted = deleted,
)

private val MATERIALIZED_SNAPSHOT_TYPE: Class<MaterializedSnapshot<*>> =
    MaterializedSnapshot::class.java

private const val GATEWAY_QUERY_SERVICE_NAME = "QueryGateway"
