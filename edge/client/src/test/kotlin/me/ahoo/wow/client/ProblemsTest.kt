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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.problem.RetryAdvice
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import org.junit.jupiter.api.Test
import java.net.http.HttpHeaders
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** The problems a peer answers with, and what a retry may do (decisions D254 ③, D366 ②). */
class ProblemsTest {
    private val none = HttpHeaders.of(emptyMap()) { _, _ -> true }

    private fun read(status: Int, body: String, retryAfter: String? = null): PeerProblem {
        val headers = HttpHeaders.of(retryAfter?.let { mapOf("Retry-After" to listOf(it)) }.orEmpty()) { _, _ -> true }
        return Problems.read(status, headers, body.toByteArray())
    }

    @Test
    fun `what a retry may do should be the problem's retry member, else the catalog's of its code`() {
        read(503, """{"code":"wow.command.unknown","retry":"same-request"}""", "2").apply {
            retry.assert().isEqualTo(RetryAdvice.SAME_REQUEST)
            retryAfter.assert().isEqualTo(2.seconds)
        }
        // The member wins over the catalog; its wait is the catalog's without a Retry-After.
        read(503, """{"code":"wow.command.unknown","retry":"none"}""").retry.assert().isEqualTo(RetryAdvice.NONE)
        read(429, """{"code":"wow.query.busy","retry":"new-request"}""").retryAfter.assert().isEqualTo(1.seconds)
        read(429, """{"code":"wow.query.busy"}""").retry.assert().isEqualTo(RetryAdvice.NEW_REQUEST)
        read(403, """{"code":"wow.auth.forbidden"}""").apply {
            retry.assert().isEqualTo(RetryAdvice.NONE)
            retryAfter.assert().isNull()
        }
        // A body that is no Wow problem: a proxy's 503 or 429 may be retried with the same request id.
        read(503, "Unavailable").retry.assert().isEqualTo(RetryAdvice.SAME_REQUEST)
        read(429, "Slow down").retry.assert().isEqualTo(RetryAdvice.SAME_REQUEST)
        read(500, "Oops").retry.assert().isEqualTo(RetryAdvice.NONE)
        // A gateway's bare 502 or 504 does not say whether its node took the command: the same request id is safe.
        listOf(502, 504).forEach { status ->
            read(status, "<html>Bad Gateway</html>").apply {
                retry.assert().isEqualTo(RetryAdvice.SAME_REQUEST)
                fromGateway.assert().isTrue()
            }
        }
        // A 502 or 504 of Wow's own is its code's.
        read(504, """{"code":"wow.query.timeout"}""").apply {
            retry.assert().isEqualTo(RetryAdvice.NONE)
            fromGateway.assert().isFalse()
        }
    }

    @Test
    fun `a command is retried with its request id and never at 504, a query whenever a retry may get past it`() {
        fun problem(status: Int, code: String, retry: RetryAdvice) = PeerProblem(status, code, null, null, retry, null)
        problem(503, FrameworkCodes.STORAGE_UNAVAILABLE, RetryAdvice.SAME_REQUEST).retriesCommand.assert().isTrue()
        // A wait that timed out may be sent again with the same request id, by the caller: the client does not.
        problem(504, FrameworkCodes.WAIT_TIMEOUT, RetryAdvice.SAME_REQUEST).retriesCommand.assert().isFalse()
        // A gateway's bare 502 or 504 is the caller's to send again: neither a command nor a query is sent again here.
        listOf(502, 504).forEach { status ->
            PeerProblem(status, null, null, null, RetryAdvice.SAME_REQUEST, null).apply {
                retriesCommand.assert().isFalse()
                retriesQuery.assert().isFalse()
            }
        }
        // Wow's own 502 of a command keeps its retry.
        problem(502, FrameworkCodes.COMMAND_FAILED, RetryAdvice.SAME_REQUEST).retriesCommand.assert().isTrue()
        problem(429, FrameworkCodes.QUERY_BUSY, RetryAdvice.NEW_REQUEST).retriesCommand.assert().isFalse()
        problem(429, FrameworkCodes.QUERY_BUSY, RetryAdvice.NEW_REQUEST).retriesQuery.assert().isTrue()
        problem(503, FrameworkCodes.STORAGE_UNAVAILABLE, RetryAdvice.SAME_REQUEST).retriesQuery.assert().isTrue()
        problem(400, FrameworkCodes.QUERY_INVALID, RetryAdvice.NONE).retriesQuery.assert().isFalse()
        // A state read below its minimum version is the caller's to wait for.
        problem(
            409,
            FrameworkCodes.QUERY_MIN_VERSION_NOT_REACHED,
            RetryAdvice.NEW_REQUEST
        ).retriesQuery.assert().isFalse()
    }

    @Test
    fun `a failure of the transport should be the problem of its catalog code`() {
        val failure = java.io.IOException("refused")
        PeerProblem.of(FrameworkCodes.PEER_UNAVAILABLE, "No node.", failure).apply {
            status.assert().isEqualTo(503)
            retry.assert().isEqualTo(RetryAdvice.NEW_REQUEST)
            retryAfter.assert().isEqualTo(1.seconds)
            (exception("The query") as me.ahoo.wow.api.problem.WowProblemException).cause.assert().isSameAs(failure)
        }
    }

    @Test
    fun `Retry-After should be seconds or an HTTP date, the time left until it`() {
        val now = Instant.parse("2026-10-20T10:00:00Z")
        Problems.retryAfterOf(" 3 ", now).assert().isEqualTo(3.seconds)
        Problems.retryAfterOf("-1", now).assert().isNull()
        Problems.retryAfterOf("Tue, 20 Oct 2026 10:00:05 GMT", now).assert().isEqualTo(5.seconds)
        Problems.retryAfterOf("Tue, 20 Oct 2026 09:00:00 GMT", now).assert().isEqualTo(0.seconds)
        Problems.retryAfterOf("soon", now).assert().isNull()
    }

    @Test
    fun `a body that is no problem should keep its text as the detail`() {
        val proxy = Problems.read(502, none, "Bad gateway".toByteArray())
        proxy.code.assert().isNull()
        proxy.detail.assert().isEqualTo("Bad gateway")
        Problems.read(500, none, ByteArray(0)).detail.assert().isNull()
        Problems.read(500, none, "{\"code\":1,\"result\":{\"bad\":true}}".toByteArray()).let {
            it.code.assert().isNull()
            it.result.assert().isNull()
        }
        Problems.read(500, none, "[1]".toByteArray()).detail.assert().isEqualTo("[1]")
    }

    @Test
    fun `a problem should keep its title, violations and other members, and no detail it does not have`() {
        val body = """{"type":"urn:wow:error:wow.command.invalid","title":"Invalid command","status":400,""" +
            """"code":"wow.command.invalid","violations":[{"path":"amount","code":"positive"},{"path":1}],""" +
            """"limit":{"max":5}}"""
        Problems.read(400, none, body.toByteArray()).apply {
            title.assert().isEqualTo("Invalid command")
            detail.assert().isNull()
            violations.assert().containsExactly(Violation.of("amount", "positive", ""), Violation.of("", "", ""))
            extensions.assert().isEqualTo(mapOf("limit" to mapOf("max" to 5)))
        }
        Problems.read(400, none, """{"violations":{}}""".toByteArray()).violations.assert().isEmpty()
    }

    @Test
    fun `the exception of a problem should carry its violations, so the caller reads which fields the peer refused`() {
        val body = """{"type":"urn:wow:error:wow.request.invalid","title":"Invalid request","status":400,""" +
            """"code":"wow.request.invalid","violations":[{"path":"name","code":"not-blank","message":"blank"}]}"""
        val failure = Problems.read(400, none, body.toByteArray()).exception("The catalog's items")
        (failure as WowProblemException).code.assert().isEqualTo(FrameworkCodes.REQUEST_INVALID)
        failure.violations.assert().containsExactly(Violation.of("name", "not-blank", "blank"))
    }
}
