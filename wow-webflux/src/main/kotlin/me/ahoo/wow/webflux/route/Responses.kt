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
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.command.CommandRequestHeaders.WOW_ERROR_CODE
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.command.isSse
import org.reactivestreams.Publisher
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

fun Throwable.toResponseEntity(): ResponseEntity<ErrorInfo> {
    val errorInfo = toErrorInfo()
    val status = errorInfo.toHttpStatus()
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .header(WOW_ERROR_CODE, errorInfo.errorCode)
        .body(errorInfo)
}

fun ErrorInfo.toServerResponse(): Mono<ServerResponse> {
    val status = toHttpStatus()
    return ServerResponse.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .header(WOW_ERROR_CODE, errorCode)
        .bodyValue(this.toJsonString())
}

fun Mono<*>.toServerResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler = DefaultRequestExceptionHandler
): Mono<ServerResponse> {
    return flatMap {
        if (it is ErrorInfo) {
            return@flatMap it.toServerResponse()
        }
        ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(WOW_ERROR_CODE, ErrorInfo.SUCCEEDED)
            .bodyValue(it.toJsonString())
    }.onErrorResume {
        exceptionHandler.handle(request, it)
    }
}

fun Publisher<CommandResult>.toCommandResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler = DefaultRequestExceptionHandler
): Mono<ServerResponse> {
    if (!request.isSse()) {
        return this.toMono().toServerResponse(request, exceptionHandler)
    }

    val serverSentEventStream = this.toFlux().map {
        ServerSentEvent.builder<String>()
            .id(it.id)
            .event(it.stage.name)
            .data(it.toJsonString())
            .build()
    }.onErrorResume {
        val errorInfo = it.toErrorInfo()
        ServerSentEvent.builder<String>()
            .id(generateGlobalId())
            .event(errorInfo.errorCode)
            .data(errorInfo.errorMsg)
            .build().toMono()
    }

    return ServerResponse.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .header(WOW_ERROR_CODE, ErrorInfo.SUCCEEDED)
        .body(serverSentEventStream, ServerSentEvent::class.java)
        .onErrorResume {
            exceptionHandler.handle(request, it)
        }
}
