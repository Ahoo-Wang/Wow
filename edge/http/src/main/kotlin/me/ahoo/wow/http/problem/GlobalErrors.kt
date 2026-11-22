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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.problem.ProblemMapper
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowHttpOptions
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

/**
 * The error handling of the application's own routes (review M; 9.x
 * `DefaultGlobalExceptionHandler`): a failure that escapes a route the application serves beside Wow's is answered as
 * an RFC 9457 problem of the same shape and codes as the framework routes' ([Problem]), never Spring's default error
 * body, by the step Wow's routes take ([FailureProblems]):
 *
 * - a [CommandRejectedException] (a command an application route sent through a `CommandClient`) as its result, the
 *   status of its code in the Manifest's tables ([ErrorStatuses]) and its result as `result`;
 * - a [WowProblemException] as its code, status, result, retry and `Retry-After`;
 * - a [StorageUnavailableException] as 503 `wow.storage.unavailable` with `Retry-After`, a body larger than the
 *   codecs take as 413, as on the framework routes;
 * - a [ResponseStatusException] (no route, a method or media type not served, a body that cannot be read) as the
 *   problem of its status alone (`about:blank`), its reason as the detail for a 4xx;
 * - anything else as the first of the application's [mappers] answers it ([ProblemMapper]), else as a 500 that says
 *   nothing of its cause, which is logged.
 *
 * It runs before Spring Boot's error handler ([order]); a response already committed is left as it is. Wow's own routes
 * answer their problems themselves, so it sees what escapes the application's routes only.
 */
@InternalWowApi
class GlobalErrors(
    manifest: Manifest,
    options: WowHttpOptions = WowHttpOptions(),
    mappers: List<ProblemMapper> = emptyList(),
) : WebExceptionHandler, Ordered {
    private val problems = FailureProblems.of(manifest, ErrorStatuses(manifest, options.errorStatuses), mappers)

    override fun getOrder(): Int = ORDER

    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
        val response = exchange.response
        if (response.isCommitted) {
            return Mono.error(ex)
        }
        val problem = problemOf(ex)
        response.statusCode = HttpStatus.valueOf(problem.status)
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        problem.retryAfterHeader?.let { response.headers.set(HttpHeaders.RETRY_AFTER, it.toString()) }
        return response.writeWith(
            Mono.fromSupplier { response.bufferFactory().wrap(HttpJson.write(problem.members())) }
        )
    }

    /** The problem [failure] is answered with. */
    internal fun problemOf(failure: Throwable): Problem = problems.problemOf(failure)

    @InternalWowApi
    companion object {
        /** Before Spring Boot's `DefaultErrorWebExceptionHandler` (-1) and WebFlux's status handler (0). */
        const val ORDER: Int = -2
    }
}
