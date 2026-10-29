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

package me.ahoo.wow.client

import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.manifest.FrameworkErrors
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.net.http.HttpHeaders
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A problem a peer answered with (RFC 9457): its HTTP [status], its Wow [code] (none for one
 * that is only HTTP's, or a body that is no problem, such as a proxy's), its [detail], the command's [result] when it
 * had a ref, what a [retry] may do and after how long ([retryAfter], its `Retry-After`), and the aggregate's [version]
 * it answered: a rejection's, or the current one a state read of a minimum version was answered with
 * (`wow.query.min-version-not-reached`). A failure the client's transport met itself (no node reached, a connection
 * lost) is one too, of a catalog code, with that failure as its [cause].
 */
internal class PeerProblem(
    val status: Int,
    val code: String?,
    val detail: String?,
    val result: CommandResult?,
    val retry: Retry,
    val retryAfter: Duration?,
    val version: Int? = null,
    val cause: Throwable? = null,
) {
    /**
     * Whether the client sends a command answered so again itself, with its request id: never a
     * 504. A wait that timed out (`wow.wait.timeout`) keeps its [retry], which tells the caller that sending the command
     * again with the same request id is safe; whether it committed is unknown, so the caller decides, not the client.
     */
    val retriesCommand: Boolean get() = retry == Retry.SAME_REQUEST && status != GATEWAY_TIMEOUT

    /**
     * Whether the node refused the request before handling it, so another node of the peer may take it:
     * a 503 carrying Wow's own code of a node that is shutting down or on standby. A bare 503, or a gateway's,
     * is no proof that nothing ran.
     */
    val refusedBeforeHandling: Boolean
        get() = status == UNAVAILABLE && code in REFUSED_BEFORE_HANDLING

    /** Whether a query answered so may be run again: a retry may get past it, a query repeating nothing. */
    val retriesQuery: Boolean get() = retry != Retry.NONE

    /**
     * The exception of this problem for [what] was asked: a [CommandRejectedException] for a command's rejection,
     * else a [WowProblemException].
     */
    fun exception(what: String): RuntimeException {
        if (result?.status == CommandResult.Status.REJECTED) {
            return CommandRejectedException(result)
        }
        val message = "$what was answered HTTP $status${code?.let { " $it" }.orEmpty()}" +
            detail?.let { ": $it" }.orEmpty()
        return WowProblemException(code ?: "http.$status", status, retry, retryAfter, message, result, cause)
    }

    companion object {
        const val TOO_MANY_REQUESTS: Int = 429
        const val UNAVAILABLE: Int = 503
        const val GATEWAY_TIMEOUT: Int = 504

        /** The codes of a node that refused a request before handling it: shutting down, or on standby. */
        private val REFUSED_BEFORE_HANDLING = setOf(FrameworkErrors.NODE_CLOSING, FrameworkErrors.NODE_STANDBY)

        /** The problem of the catalog [code] the client's transport met itself, said as [detail], from [cause]. */
        fun of(code: String, detail: String, cause: Throwable): PeerProblem {
            val error = checkNotNull(FrameworkErrors.BY_CODE[code]) { "$code is no code of the error catalog." }
            return PeerProblem(error.status, code, detail, null, error.retry, error.retryAfter?.seconds, cause = cause)
        }
    }
}

/**
 * Reads problems as a Wow server writes them: what a retry may do is the problem's `retry` member, which every problem
 * carries, its wait the `Retry-After` it came with, else the error catalog's for its code. A body that is no Wow
 * problem (a proxy's, a gateway's) may be retried when it is a 503 or a 429, with the same request id.
 */
internal object Problems {
    private const val MAX_DETAIL = 512

    /** The problem of a response of [status] with [headers] and [body], whatever its body holds. */
    fun read(status: Int, headers: HttpHeaders, body: ByteArray): PeerProblem {
        val node = try {
            ClientJson.mapper.readTree(body)?.takeIf { it.isObject }
        } catch (_: JacksonException) {
            null
        }
        val result = node?.get("result")?.takeUnless { it.isNull }?.let(::resultOf)
        val code = node?.text("code")
        val retry = node?.text(RETRY)?.let { WireNames.entryOf<Retry>(it) }
            ?: Retry.SAME_REQUEST.takeIf { code == null && (status == UNAVAILABLE || status == TOO_MANY_REQUESTS) }
            ?: Retry.NONE
        val retryAfter = headers.firstValue("Retry-After").orElse(null)?.let(::retryAfterOf)
            ?: code?.let { FrameworkErrors.BY_CODE[it]?.retryAfter?.seconds }
        return PeerProblem(
            status = status,
            code = code,
            detail = node?.text("detail") ?: body.decodeToString().take(MAX_DETAIL).takeIf { it.isNotBlank() },
            result = result,
            retry = retry,
            retryAfter = retryAfter.takeIf { retry != Retry.NONE },
            version = node?.get("version")?.takeIf { it.isInt }?.asInt(),
        )
    }

    /** The problem the `error` event of a streamed list holds: its members, the status among them. */
    fun ofMembers(data: String): PeerProblem {
        val status = try {
            ClientJson.mapper.readTree(data)?.get("status")?.takeIf { it.isNumber }?.asInt()
        } catch (_: JacksonException) {
            null
        }
        return read(status ?: INTERNAL_ERROR, EMPTY_HEADERS, data.toByteArray())
    }

    private const val INTERNAL_ERROR = 500
    private const val UNAVAILABLE = PeerProblem.UNAVAILABLE
    private const val TOO_MANY_REQUESTS = PeerProblem.TOO_MANY_REQUESTS
    private const val RETRY = "retry"
    private val EMPTY_HEADERS: HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

    private fun resultOf(node: JsonNode): CommandResult? = try {
        ClientJson.mapper.treeToValue(node, CommandResult::class.java)
    } catch (_: JacksonException) {
        null
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()

    /** A `Retry-After` (RFC 9110): delay seconds, or an HTTP date, as the time left until it; null when neither. */
    internal fun retryAfterOf(value: String, now: Instant = Instant.now()): Duration? {
        val trimmed = value.trim()
        trimmed.toLongOrNull()?.let { return if (it >= 0) it.seconds else null }
        return try {
            val at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (at.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0).milliseconds
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
