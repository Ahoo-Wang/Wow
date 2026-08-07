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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.webflux.exception

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.ErrorInfoCapable
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.ErrorInfoConverterRegistrar
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.query.gateway.QueryErrorCategory
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import org.springframework.http.HttpStatus
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
        return ServerResponse.status(throwable.toWebFluxHttpStatus(errorInfo))
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
        response.statusCode = throwable.toWebFluxHttpStatus(errorInfo)
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers.set(CommonComponent.Header.ERROR_CODE, errorInfo.errorCode)
        return response.writeWith(Mono.just(response.bufferFactory().wrap(errorInfo.toJsonString().toByteArray())))
    }
}

internal fun Throwable.toWebFluxHttpStatus(errorInfo: ErrorInfo) = when (this) {
    is ErrorResponse -> statusCode
    else -> errorInfo.toWebFluxHttpStatus()
}

internal fun ErrorInfo.toWebFluxHttpStatus(): HttpStatus =
    queryErrorCategory()?.queryHttpStatus(queryErrorCode()) ?: toHttpStatus()

private fun ErrorInfo.queryErrorCategory(): QueryErrorCategory? {
    val segments = errorCode.split('.', limit = QUERY_ERROR_CODE_SEGMENTS)
    if (segments.size != QUERY_ERROR_CODE_SEGMENTS || segments.first() != QUERY_ERROR_CODE_PREFIX) {
        return null
    }
    return QueryErrorCategory.entries.firstOrNull { category -> category.name == segments[1] }
}

private fun ErrorInfo.queryErrorCode(): String =
    errorCode.split('.', limit = QUERY_ERROR_CODE_SEGMENTS).getOrElse(2) { "" }

private fun QueryErrorCategory.queryHttpStatus(code: String): HttpStatus = when (this) {
    QueryErrorCategory.ACCESS_DENIED -> HttpStatus.FORBIDDEN
    QueryErrorCategory.INVALID_QUERY,
    QueryErrorCategory.INVALID_CURSOR,
    QueryErrorCategory.UNSUPPORTED_FEATURE,
    -> HttpStatus.BAD_REQUEST

    QueryErrorCategory.BUDGET_EXCEEDED ->
        if (code == DEADLINE_EXPIRED) HttpStatus.REQUEST_TIMEOUT else HttpStatus.TOO_MANY_REQUESTS

    QueryErrorCategory.INCOMPLETE_RESULT -> HttpStatus.BAD_GATEWAY
    QueryErrorCategory.BACKEND_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
    QueryErrorCategory.BACKEND_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
    QueryErrorCategory.MAPPING_FAILURE,
    QueryErrorCategory.INTERNAL_FAILURE,
    -> HttpStatus.INTERNAL_SERVER_ERROR
}

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
private const val DEADLINE_EXPIRED = "DEADLINE_EXPIRED"
private const val QUERY_ERROR_CODE_PREFIX = "Query"
private const val QUERY_ERROR_CODE_SEGMENTS = 3
