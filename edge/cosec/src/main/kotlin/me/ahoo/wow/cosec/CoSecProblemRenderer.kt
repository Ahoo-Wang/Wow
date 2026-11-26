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

package me.ahoo.wow.cosec

import me.ahoo.cosec.runtime.http.Problem
import me.ahoo.cosec.runtime.http.ProblemRenderer
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.manifest.FrameworkErrors
import org.springframework.http.HttpStatus
import me.ahoo.wow.http.problem.Problem as WowProblem

/**
 * Writes the body of CoSec's refusals as Wow's problems (`wow.cosec.problems: wow`, #6591), so a service that decides
 * its requests itself answers them in one catalog. It writes the body alone: the status and every header stay CoSec's
 * (`Content-Type`, `CoSec-Request-Id`, `Retry-After`, the `RateLimit-*` headers).
 *
 * - The type is Wow's ([codeOf]): 401 `wow.auth.unauthenticated`, 403 `wow.auth.forbidden`, 400
 *   `wow.request.malformed`, 429 `wow.request.rate-limited`, 503 `wow.auth.unavailable`, and 500 `about:blank`, as
 *   Wow answers an unexpected failure of its own.
 * - CoSec's own `type` and `code` stay in the member `cosec`, and its `requestId` and `traceId` under their names, so
 *   a client that reads CoSec's problems reads `cosec.code` (CoSec's TypeScript client reads the top-level `code`, and
 *   does not see these refusals: whoever uses it on the service keeps CoSec's bodies).
 * - A type of CoSec's Wow does not know, one a later CoSec adds, is written as CoSec writes it
 *   ([ProblemRenderer.DEFAULT]).
 *
 * It runs on the thread that writes the response, an event loop, and only serializes; one that throws is replaced by
 * CoSec's own body, and the request stays refused.
 */
@ExperimentalWowApi
class CoSecProblemRenderer : ProblemRenderer {
    override fun render(problem: Problem): ByteArray =
        problemOf(problem)?.body() ?: ProblemRenderer.DEFAULT.render(problem)

    @ExperimentalWowApi
    companion object {
        /** The member that holds CoSec's own `type` and `code`. */
        const val COSEC: String = "cosec"

        /**
         * The framework code CoSec's [type] is written with: null for an unexpected failure (`about:blank`) and, outside
         * [KNOWN], for a type Wow does not know.
         */
        @JvmStatic
        fun codeOf(type: ProblemType): String? = when (type) {
            ProblemType.INVALID_REQUEST -> FrameworkCodes.REQUEST_MALFORMED
            ProblemType.UNAUTHORIZED,
            ProblemType.TOKEN_INVALID,
            ProblemType.TOKEN_EXPIRED,
            ProblemType.TOKEN_REVOKED,
            -> FrameworkCodes.AUTH_UNAUTHENTICATED
            ProblemType.FORBIDDEN, ProblemType.SCOPE_REJECTED -> FrameworkCodes.AUTH_FORBIDDEN
            ProblemType.TOO_MANY_REQUESTS -> FrameworkCodes.REQUEST_RATE_LIMITED
            ProblemType.UNAVAILABLE -> FrameworkCodes.AUTH_UNAVAILABLE
            else -> null
        }

        /** The types Wow writes as a problem of its own: every type CoSec 8.0.0-beta.6 answers with. */
        @JvmField
        val KNOWN: Set<ProblemType> = setOf(
            ProblemType.INVALID_REQUEST,
            ProblemType.UNAUTHORIZED,
            ProblemType.TOKEN_INVALID,
            ProblemType.TOKEN_EXPIRED,
            ProblemType.TOKEN_REVOKED,
            ProblemType.FORBIDDEN,
            ProblemType.SCOPE_REJECTED,
            ProblemType.TOO_MANY_REQUESTS,
            ProblemType.UNAVAILABLE,
            ProblemType.INTERNAL_ERROR,
        )

        /** Wow's problem of CoSec's [problem]; null for a type Wow does not know, which CoSec's body answers. */
        @OptIn(InternalWowApi::class)
        @JvmStatic
        fun problemOf(problem: Problem): WowProblem? {
            val type = problem.type
            if (type !in KNOWN) {
                return null
            }
            val code = codeOf(type)
            val extensions = linkedMapOf<String, Any?>(
                "requestId" to problem.requestId,
                "traceId" to problem.traceId,
                COSEC to linkedMapOf("type" to type.uri, "code" to type.code),
            )
            if (code == null) {
                val status = HttpStatus.INTERNAL_SERVER_ERROR
                return WowProblem.of(
                    problem.status,
                    status.reasonPhrase,
                    "The request failed with an unexpected error.",
                    null,
                    extensions = extensions,
                )
            }
            return WowProblem.of(
                problem.status,
                FrameworkErrors.TITLES.getValue(code),
                "CoSec refused the request: ${type.title}.",
                code,
                extensions = extensions,
            )
        }
    }
}
