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

package me.ahoo.wow.http.problem

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.StorageUnavailableException
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono
import kotlin.math.ceil

/**
 * The error handling of the application's own routes (review M; 9.x
 * `DefaultGlobalExceptionHandler`): a failure that escapes a route the application serves beside Wow's is answered as
 * an RFC 9457 problem of the same shape and codes as the framework routes' ([Problem]), never Spring's default error
 * body:
 *
 * - a [CommandRejectedException] (a command an application route sent through a `CommandClient`) as its result, the
 *   status of its code in the Manifest's tables ([ErrorStatuses]) and its result as `result`;
 * - a [WowProblemException] as its code, status, result and `Retry-After`;
 * - a [StorageUnavailableException] as 503 `wow.storage.unavailable` with `Retry-After`, a body larger than the
 *   codecs take as 413, as on the framework routes ([Problem.ofFailure]);
 * - a [ResponseStatusException] (no route, a method or media type not served, a body that cannot be read) as the
 *   problem of its status alone (`about:blank`), its reason as the detail for a 4xx;
 * - anything else as a 500 that says nothing of its cause, which is logged.
 *
 * It runs before Spring Boot's error handler ([order]); a response already committed is left as it is. Wow's own routes
 * answer their problems themselves, so it sees what escapes the application's routes only.
 */
@InternalWowApi
class GlobalErrors(manifest: Manifest, options: WowHttpOptions = WowHttpOptions()) : WebExceptionHandler, Ordered {
    private val statuses = ErrorStatuses(manifest, options.errorStatuses)

    override fun getOrder(): Int = ORDER

    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
        val response = exchange.response
        if (response.isCommitted) {
            return Mono.error(ex)
        }
        val problem = problemOf(ex)
        response.statusCode = HttpStatus.valueOf(problem.status)
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        problem.retryAfter?.let { response.headers.set(HttpHeaders.RETRY_AFTER, it.toString()) }
        return response.writeWith(
            Mono.fromSupplier { response.bufferFactory().wrap(HttpJson.write(problem.members())) }
        )
    }

    /** The problem [failure] is answered with. */
    internal fun problemOf(failure: Throwable): Problem = when (failure) {
        is CommandRejectedException -> statuses.problemOf(failure.result)
        is WowProblemException -> failure.result?.let(statuses::problemOf)?.copyWith(status = failure.status) ?: Problem(
            status = failure.status,
            title = statuses.title(failure.code, null, failure.status),
            detail = failure.message,
            code = failure.code,
            retryAfter = failure.retryHint.retryAfter?.let { ceil(it.inWholeMilliseconds / MILLIS_PER_SECOND).toLong() },
        )
        is ResponseStatusException -> {
            val status = HttpStatus.resolve(failure.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
            Problem.ofStatus(status, failure.reason.takeIf { status.is4xxClientError })
        }
        else -> {
            val problem = Problem.ofFailure(failure)
            if (problem.status == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                logger.error(failure) { "An application route failed with an unexpected error." }
            } else if (problem.code == FrameworkErrors.STORAGE_UNAVAILABLE) {
                logger.warn(failure) { "An application route failed: a store is unavailable." }
            }
            problem
        }
    }

    @InternalWowApi
    companion object {
        /** Before Spring Boot's `DefaultErrorWebExceptionHandler` (-1) and WebFlux's status handler (0). */
        const val ORDER: Int = -2

        private const val MILLIS_PER_SECOND = 1000.0
        private val logger = KotlinLogging.logger {}
    }
}
