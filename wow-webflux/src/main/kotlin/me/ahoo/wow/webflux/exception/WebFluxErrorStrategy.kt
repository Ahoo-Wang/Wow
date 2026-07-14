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
import me.ahoo.wow.api.exception.ErrorInfoCapable
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.ErrorInfoConverterRegistrar
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import org.springframework.http.MediaType
import org.springframework.validation.BindingResult
import org.springframework.web.ErrorResponse
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.io.FileNotFoundException
import java.util.concurrent.TimeoutException

interface WebFluxErrorStrategy {
    fun toServerResponse(request: ServerRequest, throwable: Throwable): Mono<ServerResponse>
    fun writeToExchange(exchange: ServerWebExchange, throwable: Throwable): Mono<Void>
}

object DefaultWebFluxErrorStrategy : WebFluxErrorStrategy {
    override fun toServerResponse(request: ServerRequest, throwable: Throwable): Mono<ServerResponse> {
        val errorInfo = throwable.toWebFluxErrorInfo()
        return ServerResponse.status(throwable.httpStatus(errorInfo))
            .contentType(MediaType.APPLICATION_JSON)
            .header(CommonComponent.Header.ERROR_CODE, errorInfo.errorCode)
            .bodyValue(errorInfo.toJsonString())
    }

    override fun writeToExchange(exchange: ServerWebExchange, throwable: Throwable): Mono<Void> {
        val response = exchange.response
        if (response.isCommitted) {
            return Mono.empty()
        }

        val errorInfo = throwable.toWebFluxErrorInfo()
        response.statusCode = throwable.httpStatus(errorInfo)
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers.set(CommonComponent.Header.ERROR_CODE, errorInfo.errorCode)
        return response.writeWith(Mono.just(response.bufferFactory().wrap(errorInfo.toJsonString().toByteArray())))
    }
}

private fun Throwable.httpStatus(errorInfo: ErrorInfo) =
    (this as? ErrorResponse)?.statusCode ?: errorInfo.toHttpStatus()

private fun Throwable.toWebFluxErrorInfo(): ErrorInfo {
    return when (this) {
        is BindingResult -> toBindingErrorInfo()
        is ErrorInfoCapable,
        is ErrorInfo,
        is ErrorResponse,
        is IllegalArgumentException,
        is IllegalStateException,
        is TimeoutException,
        is FileNotFoundException,
        -> toErrorInfo()

        else -> if (ErrorInfoConverterRegistrar.get(javaClass) != null) {
            toErrorInfo()
        } else {
            ErrorInfo.of(ErrorCodes.INTERNAL_SERVER_ERROR, UNEXPECTED_SERVER_ERROR_MESSAGE)
        }
    }
}

private const val UNEXPECTED_SERVER_ERROR_MESSAGE = "Unexpected server error"
