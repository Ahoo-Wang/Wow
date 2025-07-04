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

package me.ahoo.wow.webflux.route.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.CommonComponent.Header.WOW_ERROR_CODE
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEventHttpMessageWriter
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.util.concurrent.TimeoutException

class CommandResponsesTest {
    @Test
    fun toCommandResponse() {
        val serverRequest = MockServerRequest.builder().build()
        CommandResult(
            id = generateGlobalId(),
            stage = CommandStage.SENT,
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            requestId = generateGlobalId(),
            commandId = generateGlobalId(),
            contextName = "contextName",
            processorName = "processorName",
        ).toMono()
            .toFlux()
            .toCommandResponse(serverRequest, DefaultRequestExceptionHandler)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
                it.headers().getFirst(WOW_ERROR_CODE).assert().isEqualTo(ErrorInfo.SUCCEEDED)
            }
            .verifyComplete()
    }

    @Test
    fun toStreamCommandResponse() {
        val serverRequest = MockServerRequest.builder()
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()
        val serverHttpRequest = MockServerHttpRequest.put("").build()
        val serverWebExchange = MockServerWebExchange.builder(serverHttpRequest).build()
        val responseContext = mockk<ServerResponse.Context> {
            every {
                messageWriters()
            } returns listOf(ServerSentEventHttpMessageWriter())
        }
        CommandResult(
            id = generateGlobalId(),
            stage = CommandStage.SENT,
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            requestId = generateGlobalId(),
            commandId = generateGlobalId(),
            contextName = "contextName",
            processorName = "processorName",
        ).toMono()
            .toFlux()
            .toCommandResponse(serverRequest, DefaultRequestExceptionHandler)
            .flatMap {
                it.writeTo(serverWebExchange, responseContext)
            }
            .test()
            .verifyComplete()
    }

    @Test
    fun toStreamCommandResponseTimeout() {
        val serverRequest = MockServerRequest.builder()
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()
        val serverHttpRequest = MockServerHttpRequest.put("").build()
        val serverWebExchange = MockServerWebExchange.builder(serverHttpRequest).build()
        val responseContext = mockk<ServerResponse.Context> {
            every {
                messageWriters()
            } returns listOf(ServerSentEventHttpMessageWriter())
        }
        CommandResult(
            id = generateGlobalId(),
            stage = CommandStage.SENT,
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            requestId = generateGlobalId(),
            commandId = generateGlobalId(),
            contextName = "contextName",
            processorName = "processorName",
        ).toMono()
            .toFlux()
            .doOnNext {
                throw TimeoutException()
            }
            .toCommandResponse(serverRequest, DefaultRequestExceptionHandler)
            .flatMap {
                it.writeTo(serverWebExchange, responseContext)
            }
            .test()
            .verifyComplete()
    }

    @Test
    fun `verify sse headers`() {
        val mockRequest = MockServerRequest.builder()
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()
        val serverHttpRequest = MockServerHttpRequest.put("").build()
        val serverWebExchange = MockServerWebExchange.builder(serverHttpRequest).build()
        val responseContext = mockk<ServerResponse.Context> {
            every {
                messageWriters()
            } returns listOf(ServerSentEventHttpMessageWriter())
        }
        CommandResult(
            id = generateGlobalId(),
            stage = CommandStage.SENT,
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            requestId = generateGlobalId(),
            commandId = generateGlobalId(),
            contextName = "contextName",
            processorName = "processorName",
        ).toMono()
            .toFlux()
            .toCommandResponse(mockRequest, DefaultRequestExceptionHandler)
            .test()
            .consumeNextWith {
                it.writeTo(serverWebExchange, responseContext).test().verifyComplete()
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(MediaType.TEXT_EVENT_STREAM)
                it.headers().getFirst(WOW_ERROR_CODE).assert().isEqualTo(ErrorInfo.SUCCEEDED)
            }
            .verifyComplete()
    }
}
