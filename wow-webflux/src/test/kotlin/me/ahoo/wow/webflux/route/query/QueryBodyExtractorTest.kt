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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.concurrent.atomic.AtomicBoolean

class QueryBodyExtractorTest {

    @Test
    fun `should extract condition via count handler`() {
        // Test condition extraction through CountQueryHandlerFunction end-to-end
        val handlerFunction = CountQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
            queryGateway = RouteTestFixtures.queryGateway,
            documentKind = QueryDocumentKind.SNAPSHOT,
            rewriteRequestCondition = DefaultRewriteRequestCondition,
            queryAdmission = RouteTestFixtures.queryAdmission,
            exceptionHandler = WebFluxRequestExceptionHandler()
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        val request = MockServerRequest.builder()
            .body(Condition.ALL.toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(org.springframework.http.HttpStatus.OK)
            }.verifyComplete()
    }

    @Test
    fun `list query should stay lazy until response body subscription`() {
        val subscribed = AtomicBoolean()
        val queryGateway = object : QueryGateway by RouteTestFixtures.queryGateway {
            override fun <R : Any> list(request: ListQueryRequest<R>): Flux<R> = Flux.defer {
                subscribed.set(true)
                @Suppress("UNCHECKED_CAST")
                Flux.just(ImmutableDynamicDocument.copyOf(mapOf("context" to "ok")) as R)
            }
        }
        val handlerFunction = ListQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
            queryGateway = queryGateway,
            documentKind = QueryDocumentKind.SNAPSHOT,
            rewriteRequestCondition = DefaultRewriteRequestCondition,
            queryAdmission = RouteTestFixtures.queryAdmission,
            exceptionHandler = WebFluxRequestExceptionHandler()
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        val request = MockServerRequest.builder()
            .body(ListQuery(condition = Condition.ALL).toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                subscribed.get().assert().isFalse()
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())
                it.writeTo(exchange, SERVER_RESPONSE_CONTEXT)
                    .test()
                    .verifyComplete()
                exchange.response.bodyAsString.block()!!.assert().contains("context")
            }.verifyComplete()
    }

    @Test
    fun `should extract list query via list handler`() {
        val handlerFunction = ListQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
            queryGateway = RouteTestFixtures.queryGateway,
            documentKind = QueryDocumentKind.SNAPSHOT,
            rewriteRequestCondition = DefaultRewriteRequestCondition,
            queryAdmission = RouteTestFixtures.queryAdmission,
            exceptionHandler = WebFluxRequestExceptionHandler()
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        val request = MockServerRequest.builder()
            .body(ListQuery(condition = Condition.ALL).toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(org.springframework.http.HttpStatus.OK)
            }.verifyComplete()
    }

    @Test
    fun `should extract paged query via paged handler`() {
        val handlerFunction = PagedQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY,
            queryGateway = RouteTestFixtures.queryGateway,
            documentKind = QueryDocumentKind.SNAPSHOT,
            rewriteRequestCondition = DefaultRewriteRequestCondition,
            queryAdmission = RouteTestFixtures.queryAdmission,
            exceptionHandler = WebFluxRequestExceptionHandler()
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        val request = MockServerRequest.builder()
            .body(PagedQuery(condition = Condition.ALL).toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(org.springframework.http.HttpStatus.OK)
            }.verifyComplete()
    }

    @Test
    fun `should extract single query and return not found when no data`() {
        // NoOpSnapshotQueryServiceFactory returns empty for single query,
        // so throwNotFoundIfEmpty() results in 404 NOT_FOUND.
        // This tests that the body extraction and query pipeline work correctly.
        val handlerFunction = SingleQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE,
            queryGateway = RouteTestFixtures.queryGateway,
            documentKind = QueryDocumentKind.SNAPSHOT,
            rewriteRequestCondition = DefaultRewriteRequestCondition,
            queryAdmission = RouteTestFixtures.queryAdmission,
            exceptionHandler = WebFluxRequestExceptionHandler()
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        val request = MockServerRequest.builder()
            .body(SingleQuery(condition = Condition.ALL).toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                // NoOp returns empty, which triggers throwNotFoundIfEmpty → 404
                it.statusCode().assert().isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND)
            }.verifyComplete()
    }

    private companion object {
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
