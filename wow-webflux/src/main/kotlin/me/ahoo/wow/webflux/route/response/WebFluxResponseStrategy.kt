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

package me.ahoo.wow.webflux.route.response

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

private object StringServerSentEventType : ParameterizedTypeReference<ServerSentEvent<String>>()

internal interface WebFluxResponseStrategy {
    fun singleJson(
        body: Mono<*>,
        request: ServerRequest,
        exceptionHandler: RequestExceptionHandler
    ): Mono<ServerResponse>

    fun <T : Any> jsonArray(
        body: Flux<T>,
        request: ServerRequest,
        exceptionHandler: RequestExceptionHandler
    ): Mono<ServerResponse>

    fun commandResult(
        body: Flux<CommandResult>,
        request: ServerRequest,
        exceptionHandler: RequestExceptionHandler
    ): Mono<ServerResponse>

    fun sse(
        body: Flux<ServerSentEvent<String>>,
        request: ServerRequest,
        exceptionHandler: RequestExceptionHandler
    ): Mono<ServerResponse>
}

internal object DefaultWebFluxResponseStrategy : WebFluxResponseStrategy {
    override fun singleJson(
        body: Mono<*>,
        request: ServerRequest,
        exceptionHandler: RequestExceptionHandler
    ): Mono<ServerResponse> {
        return body.flatMap {
            if (it is ErrorInfo) {
                return@flatMap it.toJsonResponse()
            }
            ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(ERROR_CODE, ErrorInfo.SUCCEEDED)
                .bodyValue(it.toJsonString())
        }.onErrorResume {
            exceptionHandler.handle(request, it)
        }
    }

    override fun <T : Any> jsonArray(
        body: Flux<T>,
        request: ServerRequest,
        exceptionHandler: RequestExceptionHandler
    ): Mono<ServerResponse> {
        if (request.acceptsEventStream()) {
            return body.map {
                ServerSentEvent.builder<String>()
                    .data(it.toJsonString())
                    .build()
            }.let {
                sse(it, request, exceptionHandler)
            }
        }
        return Mono.just(StreamingJsonArrayResponse(body, request, exceptionHandler))
    }

    override fun commandResult(
        body: Flux<CommandResult>,
        request: ServerRequest,
        exceptionHandler: RequestExceptionHandler
    ): Mono<ServerResponse> {
        if (!request.acceptsEventStream()) {
            return singleJson(body.next(), request, exceptionHandler)
        }

        val serverSentEventStream = body.map {
            ServerSentEvent.builder<String>()
                .id(it.id)
                .event(it.stage.name)
                .data(it.toJsonString())
                .build()
        }
        return sse(serverSentEventStream, request, exceptionHandler)
    }

    override fun sse(
        body: Flux<ServerSentEvent<String>>,
        request: ServerRequest,
        exceptionHandler: RequestExceptionHandler
    ): Mono<ServerResponse> {
        val eventStream = body.errorResume(request, exceptionHandler)
        return ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header(ERROR_CODE, ErrorInfo.SUCCEEDED)
            .body(eventStream, StringServerSentEventType)
    }
}

private fun ServerRequest.acceptsEventStream(): Boolean {
    return headers().accept().firstOrNull() == MediaType.TEXT_EVENT_STREAM
}

private fun ErrorInfo.toJsonResponse(): Mono<ServerResponse> {
    return ServerResponse.status(toHttpStatus())
        .contentType(MediaType.APPLICATION_JSON)
        .header(ERROR_CODE, errorCode)
        .bodyValue(toJsonString())
}

internal fun Flux<ServerSentEvent<String>>.errorResume(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Flux<ServerSentEvent<String>> {
    return onErrorResume {
        val errorInfo = it.toErrorInfo()
        val serverSendEventMono = ServerSentEvent.builder<String>()
            .id(generateGlobalId())
            .event(errorInfo.errorCode)
            .data(errorInfo.toJsonString())
            .build().toMono()

        exceptionHandler.handle(request, it).then(serverSendEventMono)
    }
}
