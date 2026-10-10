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

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.rest.WowHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean

interface RequestExceptionHandler {
    /** The error response of a request that failed before its response started. */
    fun handle(request: ServerRequest, throwable: Throwable): Mono<ServerResponse>

    /**
     * The error a response reports in its body, because its status is already `200`: the error event of an SSE
     * stream that fails, the error of a batch result. Since 9.3.0 it is mapped like [handle]'s response, so an
     * unexpected failure is `InternalServerError` with a generic message here too.
     *
     * By default it is [DefaultWebFluxErrorStrategy]'s mapping, without logging.
     */
    fun handleInBody(request: ServerRequest, throwable: Throwable): ErrorInfo =
        DefaultWebFluxErrorStrategy.toErrorInfo(throwable)
}

class WebFluxRequestExceptionHandler(
    private val errorStrategy: WebFluxErrorStrategy = DefaultWebFluxErrorStrategy
) : RequestExceptionHandler {
    private val log = KotlinLogging.logger {}

    fun ServerRequest.formatRequest(): String {
        return "HTTP ${method()} ${uri()}"
    }

    /** Maps [throwable] with the error strategy and logs it as [handle] logs the response it would have rendered. */
    override fun handleInBody(request: ServerRequest, throwable: Throwable): ErrorInfo {
        val errorInfo = errorStrategy.toErrorInfo(throwable)
        log.requestFailure(
            request.formatRequest(),
            throwable.httpStatus(errorInfo),
            errorInfo.errorCode,
            throwable
        )
        return errorInfo
    }

    override fun handle(request: ServerRequest, throwable: Throwable): Mono<ServerResponse> {
        return Mono.defer {
            val logged = AtomicBoolean()
            Mono.defer { errorStrategy.toServerResponse(request, throwable) }
                .doOnNext { response ->
                    if (logged.compareAndSet(false, true)) {
                        log.requestFailure(
                            request.formatRequest(),
                            response.statusCode(),
                            response.headers().getFirst(WowHeaders.ERROR_CODE),
                            throwable
                        )
                    }
                }
                .switchIfEmpty(
                    Mono.defer {
                        if (logged.compareAndSet(false, true)) {
                            log.warn(throwable) { "${request.formatRequest()} - Error response was empty." }
                        }
                        Mono.empty()
                    }
                ).doOnError { responseFailure ->
                    if (logged.compareAndSet(false, true)) {
                        log.warn(throwable) {
                            "${request.formatRequest()} - Failed to render error response: " +
                                responseFailure.singleLineMessage()
                        }
                    }
                }.doOnCancel {
                    if (logged.compareAndSet(false, true)) {
                        log.warn(throwable) { "${request.formatRequest()} - Error response rendering was cancelled." }
                    }
                }
        }
    }
}

/**
 * Logs a request's failure by what the server answered, so that the failures an operator acts on carry their stack
 * trace and the ones a client caused do not flood the log:
 *
 * - a 5xx is the server's fault: `ERROR`, with the stack trace;
 * - a 4xx whose code is `IllegalState` is too: the server reached a state it did not expect (a backend timeout, a
 *   broken invariant), not a request it refused, so it is logged at `WARN` with its stack trace, although the answer
 *   stays a 400 (the status of existing routes is part of the v9 REST contract);
 * - so is a `BatchTaskError`: a task of a batch failed, and its message only names the aggregate; the cause (the
 *   handler's or the store's exception) is in the stack trace, logged at `WARN`;
 * - any other 4xx is the request's: one `WARN` line with the message, no stack trace.
 */
internal fun KLogger.requestFailure(
    request: String,
    status: HttpStatusCode,
    errorCode: String?,
    throwable: Throwable,
) {
    when {
        status.is5xxServerError -> error(throwable) { request }
        status.is4xxClientError && errorCode !in CODES_LOGGED_WITH_STACK ->
            warn { "$request - ${throwable.singleLineMessage()}" }

        else -> warn(throwable) { request }
    }
}

/** The 4xx error codes that are the server's failure, not the request's, so their stack trace is logged. */
private val CODES_LOGGED_WITH_STACK = setOf(ErrorCodes.ILLEGAL_STATE, BatchTaskException.ERROR_CODE)

internal fun Throwable.singleLineMessage(): String = message.orEmpty().replace('\r', ' ').replace('\n', ' ')
