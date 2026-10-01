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
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean

interface RequestExceptionHandler {
    fun handle(request: ServerRequest, throwable: Throwable): Mono<ServerResponse>
}

class WebFluxRequestExceptionHandler(
    private val errorStrategy: WebFluxErrorStrategy = DefaultWebFluxErrorStrategy
) : RequestExceptionHandler {
    private val log = KotlinLogging.logger {}

    fun ServerRequest.formatRequest(): String {
        return "HTTP ${method()} ${uri()}"
    }

    override fun handle(request: ServerRequest, throwable: Throwable): Mono<ServerResponse> {
        return Mono.defer {
            val logged = AtomicBoolean()
            Mono.defer { errorStrategy.toServerResponse(request, throwable) }
                .doOnNext { response ->
                    if (logged.compareAndSet(false, true)) {
                        if (response.statusCode().is4xxClientError && !response.isServerFault()) {
                            log.warn { "${request.formatRequest()} - ${throwable.singleLineMessage()}" }
                        } else {
                            log.warn(throwable) { request.formatRequest() }
                        }
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

    /**
     * A 400 whose code is `IllegalState`: the server reached a state it did not expect (a backend timeout, a broken
     * invariant), not a request it refused, so its stack trace is logged although the wire answer stays a 400.
     */
    private fun ServerResponse.isServerFault(): Boolean =
        headers().getFirst(CommonComponent.Header.ERROR_CODE) == ErrorCodes.ILLEGAL_STATE

    private fun Throwable.singleLineMessage(): String = message.orEmpty().replace('\r', ' ').replace('\n', ' ')
}
