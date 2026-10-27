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
import me.ahoo.wow.api.client.RetryHint
import me.ahoo.wow.manifest.FrameworkErrors
import org.junit.jupiter.api.Test
import java.net.http.HttpHeaders
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** The problems a peer answers with, and their retry hints (decision D254 ③). */
class ProblemsTest {
    private val none = HttpHeaders.of(emptyMap()) { _, _ -> true }

    @Test
    fun `the hint of a code should say whether a retry may get past it, and with which request id`() {
        Problems.hintOf(FrameworkErrors.COMMAND_UNKNOWN, 503, 1.seconds).assert()
            .isEqualTo(RetryHint(true, true, 1.seconds))
        Problems.hintOf(FrameworkErrors.QUERY_BUSY, 429, null).assert().isEqualTo(RetryHint(true, false))
        Problems.hintOf(FrameworkErrors.QUERY_TIMEOUT, 504, null).retryable.assert().isFalse()
        Problems.hintOf(FrameworkErrors.AUTH_FORBIDDEN, 403, null).retryable.assert().isFalse()
        Problems.hintOf(null, 503, null).assert().isEqualTo(RetryHint(true, true))
        Problems.hintOf(null, 429, null).retryable.assert().isTrue()
        Problems.hintOf(null, 500, null).retryable.assert().isFalse()
    }

    @Test
    fun `a command is retried only with its request id and never at 504, a query only at 429`() {
        PeerProblem(503, FrameworkErrors.STORAGE_UNAVAILABLE, null, null, null).retriesCommand.assert().isTrue()
        // A wait that timed out may be sent again with the same request id, by the caller: the client does not.
        PeerProblem(504, FrameworkErrors.WAIT_TIMEOUT, null, null, null).apply {
            hint.retryable.assert().isTrue()
            hint.sameRequestId.assert().isTrue()
            retriesCommand.assert().isFalse()
        }
        PeerProblem(429, FrameworkErrors.QUERY_BUSY, null, null, null).retriesCommand.assert().isFalse()
        PeerProblem(429, FrameworkErrors.QUERY_BUSY, null, null, null).retriesQuery.assert().isTrue()
        PeerProblem(503, FrameworkErrors.STORAGE_UNAVAILABLE, null, null, null).retriesQuery.assert().isFalse()
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
}
