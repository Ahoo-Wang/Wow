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

package me.ahoo.wow.webflux.route.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionFactory
import me.ahoo.wow.webflux.route.testGlobalRouteContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.kotlin.test.test

class GenerateBIScriptHandlerFunctionTest {
    @Test
    fun `should handle generate bi script request`() {
        val handlerFunction = GenerateBIScriptHandlerFunctionFactory().create(
            testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT)
        )
        val request = MockServerRequest.builder()
            .build()

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(APPLICATION_SQL)
                val body = it.writeBody()
                body.assert().contains("-- global --")
                body.assert().contains("ENGINE = Kafka('localhost:9093'")
            }.verifyComplete()
    }

    @Test
    fun `should handle generate bi script with custom parameters`() {
        val handlerFunction =
            GenerateBIScriptHandlerFunctionFactory(
                kafkaBootstrapServers = "kafkaBootstrapServers",
                topicPrefix = "topicPrefix",
            ).create(
                testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT)
            )
        val request = MockServerRequest.builder().build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(APPLICATION_SQL)
                val body = it.writeBody()
                body.assert().contains("-- global --")
                body.assert().contains("ENGINE = Kafka('kafkaBootstrapServers'")
                body.assert().contains("'topicPrefix")
            }.verifyComplete()
    }

    private fun ServerResponse.writeBody(): String {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())
        writeTo(exchange, SERVER_RESPONSE_CONTEXT)
            .test()
            .verifyComplete()
        return exchange.response.bodyAsString.block()!!
    }

    private companion object {
        private val APPLICATION_SQL = MediaType.parseMediaType("application/sql")
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
