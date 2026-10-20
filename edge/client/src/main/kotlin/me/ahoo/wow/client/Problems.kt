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
import me.ahoo.wow.api.client.RetryHint
import me.ahoo.wow.api.client.WowProblemException
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
 * A problem a peer answered with (RFC 9457, decisions D101, D102): its HTTP [status], its Wow [code] (none for one
 * that is only HTTP's, or a body that is no problem, such as a proxy's), its [detail], the command's [result] when it
 * had a ref, and the `Retry-After` it carried.
 */
internal class PeerProblem(
    val status: Int,
    val code: String?,
    val detail: String?,
    val result: CommandResult?,
    val retryAfter: Duration?,
) {
    /** What a client may do about it: the hint of [code] and [status] ([Problems.hintOf]). */
    val hint: RetryHint get() = Problems.hintOf(code, status, retryAfter)

    /** Whether a command answered so may be sent again with its request id (decision D254 ③): never a 504. */
    val retriesCommand: Boolean get() = hint.retryable && hint.sameRequestId && status != GATEWAY_TIMEOUT

    /** Whether a query answered so may be run again (decision D254 ③): a 429 alone. */
    val retriesQuery: Boolean get() = status == TOO_MANY_REQUESTS

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
        return WowProblemException(code ?: "http.$status", status, hint, message, result)
    }

    companion object {
        const val TOO_MANY_REQUESTS: Int = 429
        const val UNAVAILABLE: Int = 503
        const val GATEWAY_TIMEOUT: Int = 504
    }
}

/** Reads problems and the hints of their codes, as `retryHintOf` of `@ahoo-wang/wow-client` does. */
internal object Problems {
    private const val MAX_DETAIL = 512

    /**
     * The codes a retry may get past, each with whether it keeps the request id: a command whose outcome is unknown, a
     * closing or busy node, a store that is down, a schedule not due yet, a contended aggregate, a wait that timed out
     * (whose command may have committed); a query over its store's bulkhead. `wow.query.timeout` is not one (decision
     * D254 ③): the same query would run as long again.
     */
    private val RETRYABLE: Map<String, Boolean> = mapOf(
        FrameworkErrors.COMMAND_UNKNOWN to true,
        FrameworkErrors.NODE_CLOSING to true,
        FrameworkErrors.STORAGE_UNAVAILABLE to true,
        FrameworkErrors.SCHEDULE_NOT_DUE to true,
        FrameworkErrors.COMMAND_BUSY to true,
        FrameworkErrors.COMMAND_CONTENDED to true,
        FrameworkErrors.WAIT_TIMEOUT to true,
        FrameworkErrors.QUERY_BUSY to false,
    )

    /**
     * The retry hint of a problem with [code] and [status]: a code of the retryable table, or a 503 or 429 without a
     * code (a proxy's), may be retried, with the [retryAfter] the response carried.
     */
    fun hintOf(code: String?, status: Int, retryAfter: Duration?): RetryHint {
        val sameRequestId = code?.let(RETRYABLE::get)
        val retryable = sameRequestId != null ||
            code == null && (status == PeerProblem.UNAVAILABLE || status == PeerProblem.TOO_MANY_REQUESTS)
        return RetryHint(retryable, retryable && sameRequestId ?: retryable, retryAfter)
    }

    /** The problem of a response of [status] with [headers] and [body], whatever its body holds. */
    fun read(status: Int, headers: HttpHeaders, body: ByteArray): PeerProblem {
        val node = try {
            ClientJson.mapper.readTree(body)?.takeIf { it.isObject }
        } catch (_: JacksonException) {
            null
        }
        val result = node?.get("result")?.takeUnless { it.isNull }?.let(::resultOf)
        return PeerProblem(
            status = status,
            code = node?.text("code"),
            detail = node?.text("detail") ?: body.decodeToString().take(MAX_DETAIL).takeIf { it.isNotBlank() },
            result = result,
            retryAfter = headers.firstValue("Retry-After").orElse(null)?.let(::retryAfterOf),
        )
    }

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
