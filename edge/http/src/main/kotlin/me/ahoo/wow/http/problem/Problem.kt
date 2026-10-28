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
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.spi.StorageUnavailableException
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait

/**
 * An RFC 9457 problem as the public port writes it: `type` is `urn:wow:error:<code>`, the
 * extension members are [code], [violations], the whole [result] of a command that has a ref, the aggregate's
 * [version] the problem answered, and the [extensions] of its rejection, each a member of its own name; one without
 * a value, or with the name of a member above (`version` among them, even on a problem without one) or of RFC 9457's
 * `instance`, is left out.
 *
 * An error answered before a command has a ref (401, a malformed header, a missing route, 406, 415) carries no
 * [result]. One that is only HTTP's (a missing route, 406, 415) has no Wow code: its type is `about:blank`, as RFC 9457
 * says of a problem that is the status alone.
 */
class Problem @InternalWowApi constructor(
    val status: Int,
    val title: String,
    val detail: String?,
    val code: String?,
    val violations: List<Violation> = emptyList(),
    val result: CommandResult? = null,
    val extensions: Map<String, Any?> = emptyMap(),
    /** The seconds after which the request may be retried, written as `Retry-After`; not a member of the body. */
    val retryAfter: Long? = null,
    /**
     * The aggregate's version the problem answered, the one meaning of the `version` member: a rejection's (the
     * version its events reached, else the one the decision saw: the next `If-Match`; none for a creation's), or the
     * current version a state read below its minimum version was answered with; null for any other problem.
     */
    val version: Int? = null,
) {
    /** `urn:wow:error:<code>`, or `about:blank` without a code. */
    val type: String get() = code?.let { TYPE_PREFIX + it } ?: ABOUT_BLANK

    /** The members as written: those without a value are left out. */
    fun members(): Map<String, Any> = buildMap {
        put("type", type)
        put("title", title)
        put("status", status)
        detail?.let { put("detail", it) }
        code?.let { put("code", it) }
        if (violations.isNotEmpty()) {
            put("violations", violations)
        }
        result?.let { put("result", it) }
        version?.let { put(VERSION, it) }
        extensions.forEach { (name, value) ->
            if (value != null && name !in this && name !in RESERVED) {
                put(name, value)
            }
        }
    }

    /** This problem as a response, with `Retry-After` when it has [retryAfter]. */
    suspend fun respond(): ServerResponse {
        val response = ServerResponse.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
        retryAfter?.let { response.header(HttpHeaders.RETRY_AFTER, it.toString()) }
        return response.bodyValueAndAwait(HttpJson.write(members()))
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is Problem && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "Problem(" +
        "status=$status, title=$title, detail=$detail, code=$code, violations=$violations, result=$result, " +
        "extensions=$extensions, retryAfter=$retryAfter, version=$version)"

    private fun valueFields(): List<Any?> = listOf(
        status,
        title,
        detail,
        code,
        violations,
        result,
        extensions,
        retryAfter,
        version,
    )

    /** This Problem with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    @Suppress("LongParameterList")
    fun copyWith(
        status: Int = this.status,
        title: String = this.title,
        detail: String? = this.detail,
        code: String? = this.code,
        violations: List<Violation> = this.violations,
        result: CommandResult? = this.result,
        extensions: Map<String, Any?> = this.extensions,
        retryAfter: Long? = this.retryAfter,
        version: Int? = this.version,
    ): Problem = Problem(status, title, detail, code, violations, result, extensions, retryAfter, version)

    companion object {
        /** A Problem of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            status: Int,
            title: String,
            detail: String?,
            code: String?,
            violations: List<Violation> = emptyList(),
            result: CommandResult? = null,
            extensions: Map<String, Any?> = emptyMap(),
            retryAfter: Long? = null,
            version: Int? = null,
        ): Problem = Problem(status, title, detail, code, violations, result, extensions, retryAfter, version)

        const val TYPE_PREFIX: String = "urn:wow:error:"
        const val ABOUT_BLANK: String = "about:blank"

        /** The member of the aggregate's version the problem answered ([version]). */
        const val VERSION: String = "version"

        /** The names no extension takes, even on a problem without their member. */
        private val RESERVED: Set<String> = setOf(INSTANCE, VERSION)

        /** RFC 9457's member that names the occurrence, which no extension takes. */
        private const val INSTANCE: String = "instance"

        /** A problem that is the HTTP [status] alone. */
        fun ofStatus(status: HttpStatus, detail: String? = null): Problem =
            Problem(status.value(), status.reasonPhrase, detail, null)

        /**
         * The problem a route answers an unexpected [failure] with: a store that is unavailable
         * is 503 `wow.storage.unavailable` with `Retry-After`, a body larger than the codecs take is 413, and anything
         * else a 500 that says nothing of its cause, which the route logs. Never Spring's bare error page.
         */
        fun ofFailure(failure: Throwable): Problem {
            val unavailable = generateSequence(failure) { it.cause }.firstOrNull { it is StorageUnavailableException }
            if (unavailable != null) {
                val status = HttpStatus.SERVICE_UNAVAILABLE
                return Problem(
                    status.value(),
                    status.reasonPhrase,
                    unavailable.message,
                    FrameworkErrors.STORAGE_UNAVAILABLE,
                    retryAfter = ErrorStatuses.STORAGE_RETRY_AFTER_SECONDS,
                )
            }
            return tooLargeProblem(failure)
                ?: ofStatus(HttpStatus.INTERNAL_SERVER_ERROR, "The request failed with an unexpected error.")
        }
    }
}

/**
 * The 413 problem of a request whose body is larger than the codecs take in memory
 * (`spring.http.codecs.max-in-memory-size`), when [failure] is that ([DataBufferLimitException], or caused
 * by one); null for any other failure.
 */
internal fun tooLargeProblem(failure: Throwable): Problem? =
    generateSequence(failure) { it.cause }.firstOrNull { it is DataBufferLimitException }?.let {
        Problem.ofStatus(HttpStatus.valueOf(CONTENT_TOO_LARGE), "The request body is larger than this server takes.")
    }

private const val CONTENT_TOO_LARGE = 413

/**
 * Answers a request with [problem] from wherever it is found out, before the command has a ref; caught by the route
 * and written as its response.
 */
internal class ProblemException(
    val problem: Problem
) : RuntimeException(problem.detail ?: problem.title, null, false, false)
