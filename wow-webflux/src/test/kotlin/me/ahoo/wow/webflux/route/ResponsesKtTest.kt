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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
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

class ResponsesKtTest {

    @Test
    fun toResponseEntity() {
        val responseEntity = IllegalArgumentException()
            .toResponseEntity()
        responseEntity.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        responseEntity.headers.contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
        responseEntity.headers.getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
    }

    @Test
    fun toServerResponse() {
        IllegalArgumentException()
            .toErrorInfo()
            .toServerResponse()
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.BAD_REQUEST)
                it.headers().contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
                it.headers().getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
            }
            .verifyComplete()
    }

    @Test
    fun commandResultToServerResponse() {
        CommandResult(
            id = generateGlobalId(),
            waitCommandId = generateGlobalId(),
            stage = CommandStage.SENT,
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            requestId = generateGlobalId(),
            commandId = generateGlobalId(),
            contextName = "contextName",
            aggregateName = "aggregateName",
            function = FunctionInfoData(
                functionKind = FunctionKind.COMMAND,
                contextName = "contextName",
                processorName = "processorName",
                name = "functionName"

            )
        ).toMono()
            .toServerResponse(MockServerRequest.builder().build(), DefaultRequestExceptionHandler)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
                it.headers().getFirst(ERROR_CODE).assert().isEqualTo(ErrorInfo.SUCCEEDED)
            }
            .verifyComplete()
    }

    @Test
    fun `verify list query sse headers`() {
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
        listOf(generateGlobalId())
            .toFlux()
            .toServerResponse(mockRequest, DefaultRequestExceptionHandler)
            .test()
            .consumeNextWith {
                it.writeTo(serverWebExchange, responseContext).test().verifyComplete()
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(MediaType.TEXT_EVENT_STREAM)
                it.headers().getFirst(ERROR_CODE).assert().isEqualTo(ErrorInfo.SUCCEEDED)
            }
            .verifyComplete()
    }

    @Test
    fun `verify list query sse error`() {
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
        IllegalArgumentException().toFlux<String>()
            .toServerResponse(mockRequest, DefaultRequestExceptionHandler)
            .test()
            .consumeNextWith {
                it.writeTo(serverWebExchange, responseContext).test().verifyComplete()
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                it.headers().contentType.assert().isEqualTo(MediaType.TEXT_EVENT_STREAM)
                it.headers().getFirst(ERROR_CODE).assert().isEqualTo(ErrorInfo.SUCCEEDED)
            }
            .verifyComplete()
    }
}
