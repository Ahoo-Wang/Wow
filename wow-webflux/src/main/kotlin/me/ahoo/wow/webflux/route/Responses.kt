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

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.command.isSse
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

object StringServerSentEventType : ParameterizedTypeReference<ServerSentEvent<String>>()

fun Throwable.toResponseEntity(): ResponseEntity<ErrorInfo> {
    val errorInfo = toErrorInfo()
    val status = errorInfo.toHttpStatus()
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .header(ERROR_CODE, errorInfo.errorCode)
        .body(errorInfo)
}

fun ErrorInfo.toServerResponse(): Mono<ServerResponse> {
    val status = toHttpStatus()
    return ServerResponse.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .header(ERROR_CODE, errorCode)
        .bodyValue(this.toJsonString())
}

fun Mono<*>.toServerResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Mono<ServerResponse> {
    return flatMap {
        if (it is ErrorInfo) {
            return@flatMap it.toServerResponse()
        }
        ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(ERROR_CODE, ErrorInfo.SUCCEEDED)
            .bodyValue(it.toJsonString())
    }.onErrorResume {
        exceptionHandler.handle(request, it)
    }
}

fun <T : Any> Flux<T>.toServerResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Mono<ServerResponse> {
    if (!request.isSse()) {
        return this.collectList().toServerResponse(request, exceptionHandler)
    }
    return this.map {
        ServerSentEvent.builder<String>()
            .data(it.toJsonString())
            .build()
    }.toEventStreamResponse(request, exceptionHandler)
}

fun Flux<ServerSentEvent<String>>.toEventStreamResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Mono<ServerResponse> {
    val eventStream = this.errorResume(request, exceptionHandler)
    return ServerResponse.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .header(ERROR_CODE, ErrorInfo.SUCCEEDED)
        .body(eventStream, StringServerSentEventType)
}

fun Flux<ServerSentEvent<String>>.errorResume(
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
