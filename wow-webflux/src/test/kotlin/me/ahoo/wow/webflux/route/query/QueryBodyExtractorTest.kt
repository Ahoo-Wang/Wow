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

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.filter.Contexts.getRawRequest
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.snapshot.SnapshotAggregationHandlerFunctionFactory
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RequestPredicates.POST
import org.springframework.web.reactive.function.server.RouterFunctions.route
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import tools.jackson.databind.node.JsonNodeFactory

class QueryBodyExtractorTest {

    @Test
    fun `aggregation body should default omitted filter and reject legacy condition or invalid filter`() {
        val queryGateway = mockk<SnapshotQueryGateway<Any>> {
            every { aggregate(any()) } returns Flux.empty()
        }
        val handler = SnapshotAggregationHandlerFunctionFactory(
            snapshotQueryGateway = { queryGateway },
            rewriteRequestFilter = DefaultRewriteRequestFilter,
            exceptionHandler = WebFluxRequestExceptionHandler(),
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.AGGREGATION,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
            ),
        )
        val client = WebTestClient.bindToRouterFunction(route(POST("/sku/snapshot/aggregation"), handler)).build()
        val metric = "\"metrics\":[{\"type\":\"COUNT\",\"alias\":\"count\"}]"

        client.post().uri("/sku/snapshot/aggregation").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{$metric}").exchange().expectStatus().isOk
        client.post().uri("/sku/snapshot/aggregation").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"condition\":{\"operator\":\"ALL\"},$metric}")
            .exchange().expectStatus().isBadRequest
        client.post().uri("/sku/snapshot/aggregation").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                "{\"elements\":[{\"path\":\"state.items\",\"filter\":{\"op\":\"EQ\",\"field\":\"sku\",\"value\":\"a\"}}],$metric}"
            ).exchange().expectStatus().isOk
        client.post().uri("/sku/snapshot/aggregation").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"filter\":{\"op\":\"MATCH_ALL\"},\"condition\":{\"operator\":\"ALL\"},$metric}")
            .exchange().expectStatus().isBadRequest
        client.post().uri("/sku/snapshot/aggregation").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"filter\":{\"op\":\"EQ\",\"field\":\"state.tags\",\"value\":[\"a\"]},$metric}")
            .exchange().expectStatus().isBadRequest
        client.post().uri("/sku/snapshot/aggregation").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                "{\"elements\":[{\"path\":\"state.items\",\"filter\":{\"op\":\"EQ\",\"field\":\"sku\",\"value\":[\"a\"]}}],$metric}"
            )
            .exchange().expectStatus().isBadRequest
        client.post().uri("/sku/snapshot/aggregation").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                "{\"elements\":[{\"path\":\"state.items\",\"filter\":{\"op\":\"NE\",\"field\":\"sku\",\"value\":{\"value\":\"a\"},\"unexpected\":true}}],$metric}"
            )
            .exchange().expectStatus().isBadRequest
    }

    @Test
    fun `should reject malformed request body as bad request`() {
        val handlerFunction = CountQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
            queryGateway = { RouteTestFixtures.snapshotQueryGateway },
            rewriteRequestFilter = DefaultRewriteRequestFilter,
            exceptionHandler = WebFluxRequestExceptionHandler()
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        WebTestClient.bindToRouterFunction(route(POST("/sku/snapshot/count"), handlerFunction)).build()
            .post()
            .uri("/sku/snapshot/count")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().valueEquals(ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
    }

    @Test
    fun `should reject unknown filter fields`() {
        val handlerFunction = CountQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
            queryGateway = { RouteTestFixtures.snapshotQueryGateway },
            rewriteRequestFilter = DefaultRewriteRequestFilter,
            exceptionHandler = WebFluxRequestExceptionHandler()
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        WebTestClient.bindToRouterFunction(route(POST("/sku/snapshot/count"), handlerFunction)).build()
            .post()
            .uri("/sku/snapshot/count")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"op":"MATCH_NONE","unexpected":true}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().valueEquals(ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
    }

    @Test
    fun `count body should reject mixed discriminators`() {
        countClient().post().uri("/sku/snapshot/count")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"op":"MATCH_ALL","operator":"ALL"}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().valueEquals(ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
    }

    @Test
    fun `empty count body should use request scope`() {
        val captured = slot<FilterExpression>()
        val queryGateway = mockk<QueryGateway<Any>> {
            every { count(capture(captured)) } returns Mono.just(0)
        }

        countClient(queryGateway).post().uri("/sku/snapshot/count")
            .header(CommandComponent.Header.TENANT_ID, "tenant-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isOk

        captured.captured.assert().isEqualTo(TenantIdFilter("tenant-1"))
    }

    @Test
    fun `count body should accept exactly one discriminator`() {
        listOf("""{"op":"MATCH_ALL"}""", """{"operator":"ALL"}""").forEach { body ->
            countClient().post().uri("/sku/snapshot/count")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isOk
        }
    }

    @Test
    fun `should accept legacy collection equality`() {
        val handlerFunction = CountQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
            queryGateway = { RouteTestFixtures.snapshotQueryGateway },
            rewriteRequestFilter = DefaultRewriteRequestFilter,
            exceptionHandler = WebFluxRequestExceptionHandler()
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        WebTestClient.bindToRouterFunction(route(POST("/sku/snapshot/count"), handlerFunction)).build()
            .post()
            .uri("/sku/snapshot/count")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"field":"state.tags","operator":"EQ","value":["a","b"]}""")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `legacy count body should invoke typed overload with request scope`() {
        val captured = slot<FilterExpression>()
        val queryGateway = mockk<QueryGateway<Any>> {
            every { count(capture(captured)) } returns Mono.just(0)
        }
        countClient(queryGateway).post().uri("/sku/snapshot/count")
            .header(CommandComponent.Header.TENANT_ID, "tenant-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"field":"state.name","operator":"EQ","value":"Wow"}""")
            .exchange()
            .expectStatus().isOk

        val rewritten = captured.captured as AndFilter
        rewritten.operands.any { it is EqualFilter }.assert().isTrue()
        rewritten.operands.any { it == TenantIdFilter("tenant-1") }.assert().isTrue()
    }

    @Test
    fun `query body should reject both filter and condition`() {
        val body = """{"filter":{"op":"MATCH_ALL"},"condition":{"operator":"ALL"}}"""
        queryClients().forEach { (path, client) ->
            client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isBadRequest
                .expectHeader().valueEquals(ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
        }
    }

    @Test
    fun `query body should reject neither filter nor condition`() {
        queryClients().forEach { (path, client) ->
            client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isBadRequest
                .expectHeader().valueEquals(ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
        }
    }

    @Test
    fun `query body should accept exactly one filter representation`() {
        listOf(
            """{"filter":{"op":"MATCH_ALL"}}""",
            """{"condition":{"operator":"ALL"}}""",
        ).forEach { body ->
            queryClients().forEach { (path, client) ->
                val expectedStatus = if (path.endsWith("/single")) HttpStatus.NOT_FOUND else HttpStatus.OK
                client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                    .expectStatus().isEqualTo(expectedStatus)
            }
        }
    }

    @Test
    fun `query unknown fields should follow representation strictness`() {
        val legacyBodies = listOf(
            """{"condition":{"operator":"ALL","unexpected":true}}""",
            """{"condition":{"operator":"ALL"},"unexpected":true}""",
            """{"condition":{"operator":"ALL"},"projection":{"unexpected":true}}""",
            """{"condition":{"operator":"ALL"},"sort":[{"field":"state.name","direction":"ASC","unexpected":true}]}""",
            """{"condition":{"operator":"ALL"},"pagination":{"unexpected":true}}""",
        )
        val canonicalBodies = listOf(
            """{"filter":{"op":"MATCH_ALL"},"unexpected":true}""",
            """{"filter":{"op":"MATCH_ALL"},"projection":{"unexpected":true}}""",
            """{"filter":{"op":"MATCH_ALL"},"sort":[{"field":"state.name","direction":"ASC","unexpected":true}]}""",
            """{"filter":{"op":"MATCH_ALL"},"pagination":{"unexpected":true}}""",
        )
        val clients = queryClients()

        legacyBodies.forEach { body ->
            clients.forEach { (path, client) ->
                val expectedStatus = if (path.endsWith("/single")) HttpStatus.NOT_FOUND else HttpStatus.OK
                client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                    .expectStatus().isEqualTo(expectedStatus)
            }
        }
        canonicalBodies.forEach { body ->
            clients.forEach { (path, client) ->
                client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                    .expectStatus().isBadRequest
                    .expectHeader().valueEquals(ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            }
        }
    }

    @Test
    fun `canonical filter property should reject legacy condition JSON`() {
        val body = """{"filter":{"operator":"ALL"}}"""

        queryClients().forEach { (path, client) ->
            client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isBadRequest
                .expectHeader().valueEquals(ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
        }
    }

    @Test
    fun `should reject collection equality in new filter payloads`() {
        val handlerFunction = CountQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
            queryGateway = { RouteTestFixtures.snapshotQueryGateway },
            rewriteRequestFilter = DefaultRewriteRequestFilter,
            exceptionHandler = WebFluxRequestExceptionHandler()
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        val client = WebTestClient.bindToRouterFunction(route(POST("/sku/snapshot/count"), handlerFunction)).build()
        listOf("EQ", "NE").forEach { operator ->
            client.post()
                .uri("/sku/snapshot/count")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"op":"$operator","field":"state.tags","value":["a","b"]}""")
                .exchange()
                .expectStatus().isBadRequest
                .expectHeader().valueEquals(ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
        }
    }

    @Test
    fun `should extract condition via count handler`() {
        // Test condition extraction through CountQueryHandlerFunction end-to-end
        val handlerFunction = CountQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
            queryGateway = { RouteTestFixtures.snapshotQueryGateway },
            rewriteRequestFilter = DefaultRewriteRequestFilter,
            exceptionHandler = WebFluxRequestExceptionHandler()
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        val request = MockServerRequest.builder()
            .body(Condition.ALL.toFilterExpression().toMono())

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(org.springframework.http.HttpStatus.OK)
            }.verifyComplete()
    }

    @Test
    fun `list query should keep raw request context until response body subscription`() {
        val queryGateway = mockk<QueryGateway<Any>> {
            every {
                dynamicList(any())
            } returns Flux.deferContextual {
                it.getRawRequest<ServerRequest>().assert().isNotNull()
                Flux.just(JsonNodeFactory.instance.objectNode().put("context", "ok"))
            }
        }
        val handlerFunction = ListQueryHandlerFunctionFactory(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
            queryGateway = { queryGateway },
            rewriteRequestFilter = DefaultRewriteRequestFilter,
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
            queryGateway = { RouteTestFixtures.snapshotQueryGateway },
            rewriteRequestFilter = DefaultRewriteRequestFilter,
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
            queryGateway = { RouteTestFixtures.snapshotQueryGateway },
            rewriteRequestFilter = DefaultRewriteRequestFilter,
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
            queryGateway = { RouteTestFixtures.snapshotQueryGateway },
            rewriteRequestFilter = DefaultRewriteRequestFilter,
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
        private fun countClient(queryGateway: QueryGateway<*> = RouteTestFixtures.snapshotQueryGateway): WebTestClient {
            val handler = CountQueryHandlerFunctionFactory(
                BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                { queryGateway },
                DefaultRewriteRequestFilter,
                WebFluxRequestExceptionHandler(),
            ).create(
                testAggregateRouteContract(
                    BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                    RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                ),
            )
            return WebTestClient.bindToRouterFunction(route(POST("/sku/snapshot/count"), handler)).build()
        }

        private fun queryClients(): List<Pair<String, WebTestClient>> {
            fun client(path: String, handler: HandlerFunction<ServerResponse>) =
                WebTestClient.bindToRouterFunction(route(POST(path), handler)).build()

            val metadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            val queryGateway = RouteTestFixtures.snapshotQueryGateway
            val exceptionHandler = WebFluxRequestExceptionHandler()
            val single = SingleQueryHandlerFunctionFactory(
                BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE,
                { queryGateway },
                DefaultRewriteRequestFilter,
                exceptionHandler,
            ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE, metadata))
            val list = ListQueryHandlerFunctionFactory(
                BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
                { queryGateway },
                DefaultRewriteRequestFilter,
                exceptionHandler,
            ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY, metadata))
            val paged = PagedQueryHandlerFunctionFactory(
                BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY,
                { queryGateway },
                DefaultRewriteRequestFilter,
                exceptionHandler,
            ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY, metadata))

            return listOf(
                "/sku/snapshot/single" to client("/sku/snapshot/single", single),
                "/sku/snapshot/list" to client("/sku/snapshot/list", list),
                "/sku/snapshot/paged" to client("/sku/snapshot/paged", paged),
            )
        }

        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
