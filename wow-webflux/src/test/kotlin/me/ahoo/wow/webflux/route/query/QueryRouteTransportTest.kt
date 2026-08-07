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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.webflux.route.query

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.openapi.BatchComponent
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryHandler
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.gateway.QueryAuthority
import me.ahoo.wow.query.gateway.QueryCall
import me.ahoo.wow.query.gateway.QueryCallResolutionRequest
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryExecutionMode
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.gateway.QueryTrustedContextRequest
import me.ahoo.wow.query.gateway.QueryValidationMode
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunction
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunction
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class QueryRouteTransportTest {
    @Test
    fun `all generic query route families should publish exact transport markers`() {
        QueryDocumentKind.entries.forEach { documentKind ->
            val probe = TransportProbeQueryHandler(documentKind)
            val handlers = listOf(
                SingleQueryHandlerFunction(
                    MOCK_AGGREGATE_METADATA,
                    probe,
                    documentKind,
                    DefaultRewriteRequestCondition,
                    WebFluxRequestExceptionHandler(),
                    { it },
                ) to request(SingleQuery(Condition.all())),
                ListQueryHandlerFunction(
                    MOCK_AGGREGATE_METADATA,
                    probe,
                    documentKind,
                    DefaultRewriteRequestCondition,
                    WebFluxRequestExceptionHandler(),
                    { it },
                ) to request(ListQuery(condition = Condition.all())),
                PagedQueryHandlerFunction(
                    MOCK_AGGREGATE_METADATA,
                    probe,
                    documentKind,
                    DefaultRewriteRequestCondition,
                    WebFluxRequestExceptionHandler(),
                    { it },
                ) to request(PagedQuery(condition = Condition.all())),
                CountQueryHandlerFunction(
                    MOCK_AGGREGATE_METADATA,
                    probe,
                    documentKind,
                    DefaultRewriteRequestCondition,
                    WebFluxRequestExceptionHandler(),
                ) to request(Condition.all()),
            )

            handlers.forEach { (handler, request) -> handler.writeAndVerify(request) }

            probe.calls.map(QueryCallResolutionRequest::queryType).assert().containsExactly(
                QueryType.DYNAMIC_SINGLE,
                QueryType.DYNAMIC_LIST,
                QueryType.DYNAMIC_PAGED,
                QueryType.COUNT,
            )
            probe.calls.forEach { resolution ->
                resolution.target.assert().isEqualTo(QueryTarget(MOCK_AGGREGATE_METADATA, documentKind))
            }
            probe.resolvedCalls.forEach { call ->
                call.resourceScope.tenantId.assert().isEqualTo("tenant-1")
                call.resourceScope.ownerId.assert().isEqualTo("owner-1")
                call.resourceScope.spaceId.assert().isEqualTo("space-1")
            }
        }
    }

    @Test
    fun `both get load routes should publish the same typed transport marker`() {
        val resolvers = QueryWebTransportResolvers {
            Mono.just(QueryAuthority.System("route-test", "transport-marker-test"))
        }
        val calls = mutableListOf<QueryCallResolutionRequest>()
        val resolvedLoadCalls = mutableListOf<QueryCall>()
        val snapshotTarget = QueryTarget(MOCK_AGGREGATE_METADATA, QueryDocumentKind.SNAPSHOT)
        val snapshotHandler = mockk<SnapshotQueryHandler> {
            every { dynamicSingle(any(), any()) } returns resolvers
                .resolve(trustedRequest(snapshotTarget, QueryType.DYNAMIC_SINGLE))
                .map { context -> context.call }
                .doOnNext { call -> calls += QueryCallResolutionRequest(call.target, QueryType.DYNAMIC_SINGLE) }
                .doOnNext(resolvedLoadCalls::add)
                .map { mutableMapOf("value" to "snapshot").toDynamicDocument() }
        }
        val snapshotRequest = MockServerRequest.builder()
            .pathVariable(MessageRecords.OWNER_ID, "owner-1")
            .pathVariable(MessageRecords.ID, "aggregate-1")
            .header(CommonComponent.Header.SPACE_ID, "space-1")
            .build()
        LoadSnapshotHandlerFunction(
            RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
            snapshotHandler,
            WebFluxRequestExceptionHandler(),
        ).writeAndVerify(snapshotRequest)

        val eventTarget = QueryTarget(MOCK_AGGREGATE_METADATA, QueryDocumentKind.EVENT_STREAM)
        val eventQuery = slot<IListQuery>()
        val eventHandler = mockk<EventStreamQueryHandler> {
            every { dynamicList(any(), capture(eventQuery)) } returns resolvers
                .resolve(trustedRequest(eventTarget, QueryType.DYNAMIC_LIST))
                .map { context -> context.call }
                .doOnNext { call -> calls += QueryCallResolutionRequest(call.target, QueryType.DYNAMIC_LIST) }
                .doOnNext(resolvedLoadCalls::add)
                .flatMapMany { Flux.just(mutableMapOf("value" to "event").toDynamicDocument()) }
        }
        val eventRequest = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, "aggregate-1")
            .pathVariable(BatchComponent.PathVariable.HEAD_VERSION, "0")
            .pathVariable(BatchComponent.PathVariable.TAIL_VERSION, Int.MAX_VALUE.toString())
            .build()
        LoadEventStreamHandlerFunction(
            MOCK_AGGREGATE_METADATA,
            eventHandler,
            WebFluxRequestExceptionHandler(),
        ).writeAndVerify(eventRequest)

        calls.assert().containsExactly(
            QueryCallResolutionRequest(snapshotTarget, QueryType.DYNAMIC_SINGLE),
            QueryCallResolutionRequest(eventTarget, QueryType.DYNAMIC_LIST),
        )
        eventQuery.captured.limit.assert().isZero()
        resolvedLoadCalls.map { call -> call.resourceScope.tenantId }.assert().containsExactly("(0)", "(0)")
    }

    private fun request(body: Any): ServerRequest = MockServerRequest.builder()
        .pathVariable(MessageRecords.TENANT_ID, "tenant-1")
        .pathVariable(MessageRecords.OWNER_ID, "owner-1")
        .header(CommonComponent.Header.SPACE_ID, "space-1")
        .body(body.toMono())

    private fun HandlerFunction<ServerResponse>.writeAndVerify(request: ServerRequest) {
        handle(request)
            .flatMap { response ->
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/query").build())
                response.writeTo(exchange, SERVER_RESPONSE_CONTEXT)
            }
            .test()
            .verifyComplete()
    }

    private class TransportProbeQueryHandler(
        private val documentKind: QueryDocumentKind,
    ) : QueryHandler<Any> {
        val calls = mutableListOf<QueryCallResolutionRequest>()
        val resolvedCalls = mutableListOf<QueryCall>()
        private val resolvers = QueryWebTransportResolvers {
            Mono.just(QueryAuthority.System("route-test", "transport-marker-test"))
        }

        override fun handle(context: QueryContext<*, *>): Mono<Void> = Mono.error(UnsupportedOperationException())

        override fun single(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<Any> = unsupported()

        override fun dynamicSingle(
            namedAggregate: NamedAggregate,
            singleQuery: ISingleQuery,
        ): Mono<DynamicDocument> = resolve(namedAggregate, QueryType.DYNAMIC_SINGLE)
            .map { mutableMapOf("value" to "single").toDynamicDocument() }

        override fun list(namedAggregate: NamedAggregate, listQuery: IListQuery): Flux<Any> = unsupportedFlux()

        override fun dynamicList(
            namedAggregate: NamedAggregate,
            listQuery: IListQuery,
        ): Flux<DynamicDocument> = resolve(namedAggregate, QueryType.DYNAMIC_LIST)
            .flatMapMany { Flux.just(mutableMapOf("value" to "list").toDynamicDocument()) }

        override fun paged(namedAggregate: NamedAggregate, pagedQuery: IPagedQuery): Mono<PagedList<Any>> = unsupported()

        override fun dynamicPaged(
            namedAggregate: NamedAggregate,
            pagedQuery: IPagedQuery,
        ): Mono<PagedList<DynamicDocument>> = resolve(namedAggregate, QueryType.DYNAMIC_PAGED)
            .map { PagedList.empty() }

        override fun count(namedAggregate: NamedAggregate, condition: Condition): Mono<Long> =
            resolve(namedAggregate, QueryType.COUNT).thenReturn(0)

        private fun resolve(namedAggregate: NamedAggregate, queryType: QueryType): Mono<QueryCall> {
            val resolution = QueryCallResolutionRequest(QueryTarget(namedAggregate, documentKind), queryType)
            calls += resolution
            return resolvers.resolve(trustedRequest(resolution.target, queryType))
                .map { context -> context.call }
                .doOnNext(resolvedCalls::add)
        }

        private fun <T : Any> unsupported(): Mono<T> = Mono.error(UnsupportedOperationException())

        private fun <T : Any> unsupportedFlux(): Flux<T> = Flux.error(UnsupportedOperationException())
    }

    private companion object {
        fun trustedRequest(target: QueryTarget, queryType: QueryType): QueryTrustedContextRequest =
            QueryTrustedContextRequest(
                QueryCallResolutionRequest(target, queryType),
                QueryExecutionMode.LEGACY,
                QueryValidationMode.COMPATIBLE,
            )

        val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()

            override fun messageWriters() = strategies.messageWriters()

            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
