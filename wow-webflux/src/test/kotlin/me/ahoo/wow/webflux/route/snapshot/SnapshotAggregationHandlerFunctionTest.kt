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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.query.DefaultRewriteRequestCondition
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RequestPredicates.POST
import org.springframework.web.reactive.function.server.RouterFunctions.route
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class SnapshotAggregationHandlerFunctionTest {
    @Test
    fun `should reject legacy condition payload`() {
        val handler = aggregationHandler()

        WebTestClient.bindToRouterFunction(route(POST("/sku/snapshot/aggregation"), handler)).build()
            .post()
            .uri("/sku/snapshot/aggregation")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"condition":{"operator":"ALL"},"metrics":[{"type":"COUNT","alias":"count"}]}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `should reject collection equality in element filter`() {
        val handler = aggregationHandler()

        WebTestClient.bindToRouterFunction(route(POST("/sku/snapshot/aggregation"), handler)).build()
            .post()
            .uri("/sku/snapshot/aggregation")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"elements":[{"path":"state.orders","filter":{"op":"EQ","field":"state.orders.status","value":["PAID"]}}],"metrics":[{"type":"COUNT","alias":"count"}]}"""
            )
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `should decode a valid aggregation wire payload`() {
        val handler = aggregationHandler()

        WebTestClient.bindToRouterFunction(route(POST("/sku/snapshot/aggregation"), handler)).build()
            .post()
            .uri("/sku/snapshot/aggregation")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"filter":{"op":"MATCH_ALL"},"metrics":[{"type":"COUNT","alias":"count"}]}""")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `should handle json and sse aggregation requests`() {
        val handler = aggregationHandler()
        listOf(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).forEach { accept ->
            val request = MockServerRequest.builder()
                .header(HttpHeaders.ACCEPT, accept.toString())
                .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
                .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
                .pathVariable(MessageRecords.SPACE_ID, generateGlobalId())
                .body(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))).toMono())

            handler.handle(request).test()
                .assertNext { response ->
                    response.statusCode().assert().isEqualTo(HttpStatus.OK)
                    response.headers().contentType.assert().isEqualTo(accept)
                    val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())
                    response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()
                }
                .verifyComplete()
        }
    }

    private fun aggregationHandler() = SnapshotAggregationHandlerFunctionFactory(
        RouteTestFixtures.snapshotQueryHandler,
        DefaultRewriteRequestCondition,
        WebFluxRequestExceptionHandler(),
    ).create(
        testAggregateRouteContract(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.AGGREGATION,
            aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
        ),
    )

    private companion object {
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
