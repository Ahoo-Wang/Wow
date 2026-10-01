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
import me.ahoo.wow.openapi.CommonComponent
import org.springframework.core.Ordered
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.validation.BindingResult
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono

class DefaultGlobalExceptionHandler(
    private val errorStrategy: WebFluxErrorStrategy = DefaultWebFluxErrorStrategy
) : WebExceptionHandler, Ordered {
    private val log = KotlinLogging.logger {}

    /**
     * Writes the error and logs it by the status it was answered with ([requestFailure]): a 404 for a missing static
     * resource is one line, a 5xx keeps its stack trace. A response committed before the error, or one whose status
     * cannot be read, is logged with the stack trace.
     */
    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
        val request = exchange.request.formatRequest()
        if (exchange.response.isCommitted) {
            log.warn(ex) { "$request - Response already committed." }
            return errorStrategy.writeToExchange(exchange, ex)
        }
        return Mono.defer { errorStrategy.writeToExchange(exchange, ex) }
            .doFinally {
                val response = exchange.response
                val status = runCatching { response.statusCode }.getOrNull()
                if (status == null) {
                    log.warn(ex) { request }
                } else {
                    val errorCode = runCatching { response.headers.getFirst(CommonComponent.Header.ERROR_CODE) }
                        .getOrNull()
                    log.requestFailure(request, status, errorCode, ex)
                }
            }
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
