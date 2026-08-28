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

package me.ahoo.wow.webflux.route.snapshot

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.filter.Contexts.getRawRequest
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.query.DefaultRewriteRequestFilter
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toMono
import java.util.concurrent.atomic.AtomicBoolean

class SnapshotAggregationHandlerFunctionTest {
    @Test
    fun `aggregation route should stream handler rows`() {
        val subscribed = AtomicBoolean()
        val gateway = mockk<SnapshotQueryGateway> {
            every { aggregate(any(), any()) } returns Flux.deferContextual {
                it.getRawRequest<MockServerRequest>().assert().isNotNull()
                subscribed.set(true)
                Flux.just(mutableMapOf("count" to 1L).toDynamicDocument())
            }
        }
        val function = SnapshotAggregationHandlerFunctionFactory(
            snapshotQueryGateway = gateway,
            rewriteRequestFilter = DefaultRewriteRequestFilter,
            exceptionHandler = WebFluxRequestExceptionHandler(),
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.AGGREGATION,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
            ),
        )
        val request = MockServerRequest.builder().body(
            AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))).toMono(),
        )

        val response = function.handle(request).block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

        response.statusCode().assert().isEqualTo(HttpStatus.OK)
        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()
        subscribed.get().assert().isTrue()
    }

    private companion object {
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
