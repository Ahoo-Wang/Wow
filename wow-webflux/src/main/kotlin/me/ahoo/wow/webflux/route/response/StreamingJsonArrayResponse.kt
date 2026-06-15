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
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

internal class StreamingJsonArrayResponse<T : Any>(
    private val body: Flux<T>,
    private val request: ServerRequest,
    private val exceptionHandler: RequestExceptionHandler
) : ServerResponse {
    private val headers = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set(ERROR_CODE, ErrorInfo.SUCCEEDED)
    }
    private val cookies = LinkedMultiValueMap<String, ResponseCookie>()

    override fun statusCode(): HttpStatusCode {
        return HttpStatus.OK
    }

    override fun headers(): HttpHeaders {
        return headers
    }

    override fun cookies(): MultiValueMap<String, ResponseCookie> {
        return cookies
    }

    override fun writeTo(exchange: ServerWebExchange, context: ServerResponse.Context): Mono<Void> {
        return body.switchOnFirst { signal, flux ->
            when {
                signal.isOnError -> exceptionHandler.handle(request, signal.throwable!!)
                    .flatMap {
                        it.writeTo(exchange, context)
                    }.flux()
                else -> writeJsonArray(exchange, flux).flux()
            }
        }.then()
    }

    private fun writeJsonArray(exchange: ServerWebExchange, body: Flux<T>): Mono<Void> {
        val response = exchange.response
        response.statusCode = statusCode()
        response.headers.addAll(headers)
        response.cookies.addAll(cookies)

        val bufferFactory = response.bufferFactory()
        val jsonValues = body
            .map { it.toJsonString() }
            .index()
            .map {
                if (it.t1 > 0L) {
                    ",${it.t2}"
                } else {
                    it.t2
                }
            }

        val dataBuffers = Flux.concat(
            Mono.just("[").map { bufferFactory.wrapString(it) },
            jsonValues.map { bufferFactory.wrapString(it) },
            Mono.just("]").map { bufferFactory.wrapString(it) }
        )

        return response.writeWith(dataBuffers)
    }

    private fun DataBufferFactory.wrapString(value: String): DataBuffer {
        return wrap(value.toByteArray(StandardCharsets.UTF_8))
    }
}
