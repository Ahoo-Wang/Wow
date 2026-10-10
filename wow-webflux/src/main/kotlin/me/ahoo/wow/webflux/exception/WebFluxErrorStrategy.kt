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
import me.ahoo.wow.rest.WowHeaders
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.validation.BindingResult
import org.springframework.web.ErrorResponse
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.io.FileNotFoundException
import java.util.concurrent.TimeoutException

/**
 * The one mapping from a failure to what an HTTP client sees.
 *
 * Every error Wow's routes report goes through it: the error response of a JSON route ([toServerResponse]), a failure
 * outside the routes ([writeToExchange]), and, since 9.3.0, the error event of an SSE stream and the error of a batch
 * result ([toErrorInfo]), so one failure gets the same error code and message on every route.
 */
interface WebFluxErrorStrategy {
    fun toServerResponse(request: ServerRequest, throwable: Throwable): Mono<ServerResponse>
    fun writeToExchange(exchange: ServerWebExchange, throwable: Throwable): Mono<Void>

    /**
     * The error code and message reported for [throwable] where the response has no status of its own: an SSE error
     * event, the error of a batch result. A failure the strategy does not classify is `InternalServerError` with a
     * generic message, never the exception's own.
     *
     * A strategy that changes the codes or messages of [toServerResponse] overrides this too; by default it is
     * [DefaultWebFluxErrorStrategy]'s mapping.
     */
    fun toErrorInfo(throwable: Throwable): ErrorInfo = throwable.toWebFluxErrorInfo()
}

object DefaultWebFluxErrorStrategy : WebFluxErrorStrategy {
    override fun toServerResponse(request: ServerRequest, throwable: Throwable): Mono<ServerResponse> {
        val errorInfo = toErrorInfo(throwable)
        return ServerResponse.status(throwable.httpStatus(errorInfo))
            .contentType(MediaType.APPLICATION_JSON)
            .header(WowHeaders.ERROR_CODE, errorInfo.errorCode)
            .bodyValue(errorInfo.toJsonString())
    }

    override fun writeToExchange(exchange: ServerWebExchange, throwable: Throwable): Mono<Void> {
        val response = exchange.response
        if (response.isCommitted) {
            return Mono.empty()
        }

        val errorInfo = toErrorInfo(throwable)
        response.statusCode = throwable.httpStatus(errorInfo)
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers.set(WowHeaders.ERROR_CODE, errorInfo.errorCode)
        return response.writeWith(Mono.just(response.bufferFactory().wrap(errorInfo.toJsonString().toByteArray())))
    }
}

/** The status for [errorInfo] of this failure: the one an [ErrorResponse] carries, else its error code's. */
internal fun Throwable.httpStatus(errorInfo: ErrorInfo): HttpStatusCode =
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
