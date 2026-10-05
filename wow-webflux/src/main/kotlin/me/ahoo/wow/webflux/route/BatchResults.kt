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

package me.ahoo.wow.webflux.route

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.openapi.BatchResult
import me.ahoo.wow.webflux.exception.DefaultWebFluxErrorStrategy
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

private val log = KotlinLogging.logger("Wow.BatchResult")

/**
 * The batch's result: the highest id processed and how many, or, when the batch fails, its error mapped and logged by
 * [RequestExceptionHandler.handleInBody], like every other error of [request].
 */
fun Flux<AggregateId>.toBatchResult(
    afterId: String,
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Mono<BatchResult> = toBatchResult(afterId) { exceptionHandler.handleInBody(request, it) }

@Deprecated("Scheduled for removal in 10.0.0. Use toBatchResult(afterId, request, exceptionHandler).")
fun Flux<AggregateId>.toBatchResult(afterId: String): Mono<BatchResult> =
    toBatchResult(afterId) {
        log.warn(it) { "Reduce onError." }
        DefaultWebFluxErrorStrategy.toErrorInfo(it)
    }

internal fun Flux<AggregateId>.toBatchResult(
    afterId: String,
    toErrorInfo: (Throwable) -> ErrorInfo
): Mono<BatchResult> {
    return this.materialize().reduce(BatchResult(afterId, 0)) { acc, signal ->
        if (signal.isOnError) {
            val error = toErrorInfo(signal.throwable!!)
            return@reduce acc.copy(
                errorCode = error.errorCode,
                errorMsg = error.errorMsg
            )
        }
        if (signal.isOnNext) {
            val aggregateId = signal.get()!!
            val nextAfterId = if (aggregateId.id > acc.afterId) {
                aggregateId.id
            } else {
                acc.afterId
            }
            return@reduce BatchResult(afterId = nextAfterId, size = acc.size + 1)
        }
        acc
    }
}
