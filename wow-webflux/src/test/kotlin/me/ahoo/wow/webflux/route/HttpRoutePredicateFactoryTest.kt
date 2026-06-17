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

package me.ahoo.wow.webflux.route

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerRequest

class HttpRoutePredicateFactoryTest {

    private val factory = HttpRoutePredicateFactory()

    @Test
    fun `should create predicate matching method path and accept`() {
        val contract = routeContract(method = "POST", path = "/test/{id}", accept = MediaType.APPLICATION_JSON_VALUE)
        val predicate = factory.create(contract)
        val request = serverRequest(
            MockServerHttpRequest
                .post("/test/aggregate-id")
                .accept(MediaType.APPLICATION_JSON)
        )

        predicate.test(request).assert().isTrue()
    }

    @Test
    fun `should reject request with different method`() {
        val contract = routeContract(method = "POST", path = "/test/{id}", accept = MediaType.APPLICATION_JSON_VALUE)
        val predicate = factory.create(contract)
        val request = serverRequest(
            MockServerHttpRequest
                .get("/test/aggregate-id")
                .accept(MediaType.APPLICATION_JSON)
        )

        predicate.test(request).assert().isFalse()
    }

    @Test
    fun `should reject request with different accept`() {
        val contract = routeContract(method = "GET", path = "/test/{id}", accept = MediaType.APPLICATION_JSON_VALUE)
        val predicate = factory.create(contract)
        val request = serverRequest(
            MockServerHttpRequest
                .get("/test/aggregate-id")
                .accept(MediaType.TEXT_PLAIN)
        )

        predicate.test(request).assert().isFalse()
    }

    private fun routeContract(
        method: String,
        path: String,
        accept: String
    ): HttpRouteContract {
        return HttpRouteContract(
            routeId = "test.route",
            method = method,
            path = path,
            accept = listOf(accept),
            handlerKey = "handler.key",
            handlerMetadata = HttpRouteHandlerMetadata.None
        )
    }

    private fun serverRequest(requestBuilder: MockServerHttpRequest.BaseBuilder<*>): ServerRequest {
        val exchange = MockServerWebExchange.from(requestBuilder)
        return ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders())
    }
}
