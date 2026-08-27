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
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.response.DefaultWebFluxResponseStrategy
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import me.ahoo.wow.webflux.route.response.errorResume as responseErrorResume

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
    return DefaultWebFluxResponseStrategy.singleJson(this, request, exceptionHandler)
}

fun <T : Any> Flux<T>.toServerResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Mono<ServerResponse> {
    return DefaultWebFluxResponseStrategy.jsonArray(this, request, exceptionHandler)
}

fun Flux<ServerSentEvent<String>>.toEventStreamResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Mono<ServerResponse> {
    return DefaultWebFluxResponseStrategy.sse(this, request, exceptionHandler)
}

fun Flux<ServerSentEvent<String>>.errorResume(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Flux<ServerSentEvent<String>> {
    return this.responseErrorResume(request, exceptionHandler)
}
