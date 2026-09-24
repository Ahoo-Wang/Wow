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

package me.ahoo.wow.webflux.route.query

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.queryScope
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.getRawRequest
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
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

class QueryHandlerSupportTest {
    private val scope = TenantIdFilter("tenant-id")
    private val support = QueryHandlerSupport(
        aggregateMetadata = MOCK_AGGREGATE_METADATA,
        queryRequestScope = QueryRequestScope { _, _ -> scope },
        exceptionHandler = WebFluxRequestExceptionHandler(),
        guard = HttpQueryGuard(idleTimeout = java.time.Duration.ZERO),
    )

    @Test
    fun `mono should expose the query scope and raw request to the gateway call`() {
        val request = MockServerRequest.builder().body(SingleQuery(IdFilter("id")).toMono())
        var observed: Pair<FilterExpression, ServerRequest?>? = null

        val status = support.mono(request, QueryBodyExtractor.SINGLE_QUERY_EXTRACTOR, QueryType.SINGLE) {
            Mono.deferContextual { context ->
                observed = context.queryScope() to context.getRawRequest()
                Mono.just("row")
            }
        }.render()

        status.assert().isEqualTo(HttpStatus.OK)
        val (observedScope, observedRequest) = observed!!
        observedScope.assert().isEqualTo(scope)
        observedRequest.assert().isSameAs(request)
    }

    @Test
    fun `mono should map an empty result to not found only when requested and otherwise stay empty`() {
        support.mono(
            MockServerRequest.builder().body(SingleQuery(IdFilter("id")).toMono()),
            QueryBodyExtractor.SINGLE_QUERY_EXTRACTOR,
            QueryType.SINGLE,
            notFoundIfEmpty = true,
        ) { Mono.empty<String>() }.render().assert().isEqualTo(HttpStatus.NOT_FOUND)

        support.mono(
            MockServerRequest.builder().body(SingleQuery(IdFilter("id")).toMono()),
            QueryBodyExtractor.SINGLE_QUERY_EXTRACTOR,
            QueryType.SINGLE,
        ) { Mono.empty<String>() }.test().verifyComplete()
    }

    @Test
    fun `mono should reject guarded queries before invoking the gateway`() {
        var invoked = false
        val status = support.mono(
            MockServerRequest.builder().body(ListQuery(IdFilter("id"), limit = -1).toMono()),
            QueryBodyExtractor.LIST_QUERY_EXTRACTOR,
            QueryType.LIST,
        ) {
            invoked = true
            Mono.just("row")
        }.render()

        status.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        invoked.assert().isFalse()
    }

    @Test
    fun `flux should prepare the query and expose the query scope and raw request to the gateway call`() {
        val request = MockServerRequest.builder().body(ListQuery(IdFilter("id"), limit = 0).toMono())
        var executed: ListQuery? = null
        var observed: Pair<FilterExpression, ServerRequest?>? = null

        val status = support.flux(
            request,
            QueryBodyExtractor.LIST_QUERY_EXTRACTOR,
            QueryType.LIST,
            prepare = { it.copy(limit = 7) },
        ) { query ->
            executed = query
            Flux.deferContextual { context ->
                observed = context.queryScope() to context.getRawRequest()
                Flux.just("row")
            }
        }.render()

        status.assert().isEqualTo(HttpStatus.OK)
        executed!!.limit.assert().isEqualTo(7)
        val (observedScope, observedRequest) = observed!!
        observedScope.assert().isEqualTo(scope)
        observedRequest.assert().isSameAs(request)
    }

    @Test
    fun `withQueryContext should write both scope and raw request`() {
        val request = MockServerRequest.builder().build()

        Mono.deferContextual { Mono.just(it.queryScope() to it.getRawRequest()) }
            .withQueryContext(scope, request)
            .test()
            .expectNext(scope to request)
            .verifyComplete()
        Flux.deferContextual { Flux.just(it.queryScope() to it.getRawRequest()) }
            .withQueryContext(scope, request)
            .test()
            .expectNext(scope to request)
            .verifyComplete()
        Mono.deferContextual { Mono.just(it.queryScope()) }
            .withQueryContext(MatchAllFilter, request)
            .test()
            .expectNext(MatchAllFilter)
            .verifyComplete()
    }

    @Test
    fun `factory support should resolve the gateway of the route aggregate`() {
        val gateway = mockk<QueryGateway<Any>>()
        val resolved = mutableListOf<AggregateMetadata<*, *>>()
        val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
        var received: Pair<AggregateMetadata<*, *>, QueryGateway<*>>? = null
        val factory = object : QueryHandlerFunctionFactorySupport<QueryGateway<Any>>(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
            queryGateway = {
                resolved += it
                gateway
            },
            handlerFunction = { aggregateMetadata, queryGateway ->
                received = aggregateMetadata to queryGateway
                handler
            },
        ) {}

        val created = factory.create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
            ),
        )

        created.assert().isSameAs(handler)
        val aggregateMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA.aggregateMetadata
        resolved.assert().containsExactly(aggregateMetadata)
        val (receivedMetadata, receivedGateway) = received!!
        receivedMetadata.assert().isSameAs(aggregateMetadata)
        receivedGateway.assert().isSameAs(gateway)
    }

    private fun Mono<ServerResponse>.render(): HttpStatus {
        val response = block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())
        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()
        return HttpStatus.valueOf(exchange.response.statusCode!!.value())
    }

    private companion object {
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
