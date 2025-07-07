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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.validation.BindingResult
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono

object GlobalExceptionHandler : WebExceptionHandler, Ordered {
    private val log = KotlinLogging.logger {}

    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
        log.warn(ex) {
            exchange.request.formatRequest()
        }

        val errorInfo = when (ex) {
            is BindingResult -> ex.toBindingErrorInfo()
            else -> ex.toErrorInfo()
        }
        val status = errorInfo.toHttpStatus()
        val response = exchange.response
        response.statusCode = status
        if (response.headers.trySet(CommonComponent.Header.WOW_ERROR_CODE, errorInfo.errorCode)) {
            response.headers.contentType = MediaType.APPLICATION_JSON
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(errorInfo.toJsonString().toByteArray())))
    }

    fun ServerHttpRequest.formatRequest(): String {
        return "HTTP $method $uri"
    }

    override fun getOrder(): Int {
        return -2
    }
}

fun BindingResult.toBindingErrorInfo(): ErrorInfo {
    val bindingErrors = fieldErrors.map { BindingError(it.field, it.defaultMessage.orEmpty()) }
    return ErrorInfo.of(ErrorCodes.ILLEGAL_ARGUMENT, errorMsg = "Field binding validation failed.", bindingErrors)
}
