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

package me.ahoo.wow.webflux.exception

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.http.MediaType
import org.springframework.validation.BindingResult
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

interface WebFluxErrorStrategy {
    fun toServerResponse(request: ServerRequest, throwable: Throwable): Mono<ServerResponse>
    fun writeToExchange(exchange: ServerWebExchange, throwable: Throwable): Mono<Void>
}

object DefaultWebFluxErrorStrategy : WebFluxErrorStrategy {
    override fun toServerResponse(request: ServerRequest, throwable: Throwable): Mono<ServerResponse> {
        return throwable.toWebFluxErrorInfo().toServerResponse()
    }

    override fun writeToExchange(exchange: ServerWebExchange, throwable: Throwable): Mono<Void> {
        val response = exchange.response
        if (response.isCommitted) {
            return Mono.empty()
        }

        val errorInfo = throwable.toWebFluxErrorInfo()
        response.statusCode = errorInfo.toHttpStatus()
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers.set(CommonComponent.Header.ERROR_CODE, errorInfo.errorCode)
        return response.writeWith(Mono.just(response.bufferFactory().wrap(errorInfo.toJsonString().toByteArray())))
    }
}

private fun Throwable.toWebFluxErrorInfo(): ErrorInfo {
    return when (this) {
        is BindingResult -> toBindingErrorInfo()
        else -> toErrorInfo()
    }
}
