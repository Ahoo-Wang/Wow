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
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.codec.ServerSentEvent
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal class StreamingServerSentEventResponse(
    private val body: Flux<ServerSentEvent<String>>,
    private val request: ServerRequest,
    private val exceptionHandler: RequestExceptionHandler
) : ServerResponse {
    private val headers = HttpHeaders().apply {
        contentType = MediaType.TEXT_EVENT_STREAM
        set(ERROR_CODE, ErrorInfo.SUCCEEDED)
    }
    private val cookies = LinkedMultiValueMap<String, ResponseCookie>()

    override fun statusCode(): HttpStatusCode = HttpStatus.OK

    override fun headers(): HttpHeaders = headers

    override fun cookies(): MultiValueMap<String, ResponseCookie> = cookies

    override fun writeTo(exchange: ServerWebExchange, context: ServerResponse.Context): Mono<Void> {
        return body.switchOnFirst { signal, flux ->
            when {
                signal.isOnError -> exceptionHandler.handle(request, signal.throwable!!)
                    .flatMap { response -> response.writeTo(exchange, context) }
                    .flux()

                else -> successResponse(flux.errorResume(request, exceptionHandler))
                    .flatMap { response -> response.writeTo(exchange, context) }
                    .flux()
            }
        }.then()
    }

    private fun successResponse(body: Flux<ServerSentEvent<String>>): Mono<ServerResponse> {
        return ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header(ERROR_CODE, ErrorInfo.SUCCEEDED)
            .body(body, SERVER_SENT_EVENT_TYPE)
    }

    private companion object {
        val SERVER_SENT_EVENT_TYPE = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
    }
}
