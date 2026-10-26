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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.http.CART_EMPTY
import me.ahoo.wow.http.CONTEXT
import me.ahoo.wow.http.cartManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.FrameworkCodes
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/** [Problem], [ProblemException] and the fallbacks of [ErrorStatuses]. */
class ProblemTest {
    private fun result(error: ErrorInfo?) = CommandResult(
        commandId = "cmd-1",
        requestId = "req-1",
        correlationId = "cmd-1",
        ref = AggregateType(CONTEXT, "cart").ref("t", "c-1"),
        hop = Hop.committed(CONTEXT),
        function = FunctionInfo(FunctionKind.COMMAND, CONTEXT, "cart", "Checkout"),
        status = if (error == null) CommandResult.Status.SUCCEEDED else CommandResult.Status.REJECTED,
        error = error,
        signalTime = 1L,
    )

    @Test
    fun `a problem with a code is typed by it and lists what it has`() {
        val problem = Problem(409, "Conflict", null, CART_EMPTY)
        problem.type.assert().isEqualTo("urn:wow:error:$CART_EMPTY")
        problem.violations.assert().isEmpty()
        problem.result.assert().isNull()
        problem.members().keys.assert().containsExactly("type", "title", "status", "code")
    }

    @Test
    fun `a rejection's extensions are members of their own names, the problem's own members left to them`() {
        val statuses = ErrorStatuses(cartManifest())
        val extensions =
            mapOf("items" to listOf(mapOf("id" to "i-1")), "code" to "other", "instance" to "/x", "none" to null)
        val result = result(ErrorInfo(CART_EMPTY, "The cart is empty.", extensions = extensions))
        val problem = statuses.problemOf(result)
        problem.extensions.assert().isEqualTo(extensions)
        problem.members().let { members ->
            members["items"].assert().isEqualTo(listOf(mapOf("id" to "i-1")))
            members["code"].assert().isEqualTo(CART_EMPTY)
            members.containsKey("instance").assert().isFalse()
            members.containsKey("none").assert().isFalse()
        }
    }

    @Test
    fun `the exception's message is the detail, else the title`() {
        ProblemException(Problem.ofStatus(HttpStatus.NOT_FOUND)).message.assert().isEqualTo("Not Found")
        ProblemException(Problem.ofStatus(HttpStatus.NOT_FOUND, "No route.")).message.assert().isEqualTo("No route.")
    }

    @Test
    fun `the problem of a result carries its error and the result`() {
        val statuses = ErrorStatuses(cartManifest())
        val result = result(ErrorInfo(CART_EMPTY, "The cart is empty."))
        statuses.problemOf(result).assert().isEqualTo(
            Problem(409, "The cart has no item.", "The cart is empty.", CART_EMPTY, result = result),
        )
        assertThrownBy<IllegalStateException> { statuses.problemOf(result(null)) }
    }

    @Test
    fun `a title falls back to the reason phrase, then to the code`() {
        val statuses = ErrorStatuses(Manifest())
        statuses.title("shop.x", null, 409).assert().isEqualTo("Conflict")
        statuses.title("shop.x", null, 299).assert().isEqualTo("shop.x")
        statuses.title(FrameworkCodes.BUSY, "unknown-context", 429).assert().isEqualTo("Command busy")
    }

    @Test
    fun `a framework code's title should be its short title, not its description`() {
        val statuses = ErrorStatuses(Manifest())
        FrameworkErrors.ALL.forEach {
            statuses.title(it.code, null, it.status).assert().isEqualTo(FrameworkErrors.TITLES.getValue(it.code))
        }
    }

    @Test
    fun `a domain command that rejected with a framework code should be answered as a failed command`() {
        // The kernel answers wow.command.failed for a domain command that chose wow.command.busy, which a client retries.
        val statuses = ErrorStatuses(cartManifest())
        val extensions = mapOf("rejectedCode" to FrameworkCodes.BUSY)
        val problem =
            statuses.problemOf(result(ErrorInfo(FrameworkErrors.COMMAND_FAILED, "Refused.", extensions = extensions)))
        problem.status.assert().isEqualTo(500)
        problem.title.assert().isEqualTo("Command failed")
        problem.detail.assert().isEqualTo("Refused.")
        problem.retryAfter.assert().isNull()
        problem.members()["rejectedCode"].assert().isEqualTo(FrameworkCodes.BUSY)
    }

    @Test
    fun `the Manifest's framework table replaces the built-in one`() {
        val statuses = ErrorStatuses(Manifest(errors = listOf(ErrorManifest(FrameworkCodes.BUSY, 503, "Busy."))))
        statuses.status(FrameworkCodes.BUSY).assert().isEqualTo(503)
        statuses.title(FrameworkCodes.BUSY, null, 503).assert().isEqualTo("Command busy")
        val problem = statuses.problemOf(FrameworkCodes.BUSY, "Later.")
        problem.assert().isEqualTo(Problem(503, "Command busy", "Later.", FrameworkCodes.BUSY, retryAfter = 1))
    }

    @Test
    fun `a problem a retry may get past carries Retry-After, which is not a member of the body`() {
        val statuses = ErrorStatuses(Manifest())
        val unavailable = statuses.problemOf(FrameworkCodes.STORAGE_UNAVAILABLE, "Down.")
        unavailable.status.assert().isEqualTo(503)
        unavailable.retryAfter.assert().isEqualTo(ErrorStatuses.STORAGE_RETRY_AFTER_SECONDS)
        unavailable.members().keys.assert().containsExactly("type", "title", "status", "detail", "code")
        // Review A3-8: the 503s and the 429 a retry with the same request id may get past.
        listOf(FrameworkCodes.UNKNOWN, FrameworkCodes.NODE_CLOSING, FrameworkCodes.BUSY).forEach { code ->
            statuses.problemOf(code, "Later.").retryAfter.assert().isEqualTo(ErrorStatuses.STORAGE_RETRY_AFTER_SECONDS)
        }
        statuses.retryAfter(FrameworkCodes.FAILED).assert().isNull()
        statuses.retryAfter(FrameworkCodes.CONTENDED).assert().isNull()
    }

    @Test
    fun `every framework code answered with 503 or 429 carries Retry-After, the min-version 409 too, and no other`() {
        val statuses = ErrorStatuses(Manifest())
        FrameworkErrors.ALL.forEach { error ->
            val expected = ErrorStatuses.STORAGE_RETRY_AFTER_SECONDS.takeIf {
                error.status == 503 || error.status == 429 || error.code == FrameworkErrors.QUERY_MIN_VERSION_NOT_REACHED
            }
            statuses.problemOf(error.code, "Later.").retryAfter.assert().describedAs(error.code).isEqualTo(expected)
        }
        ErrorStatuses.RETRYABLE.assert().contains(FrameworkErrors.SCHEDULE_NOT_DUE)
        // A query that ran out of time is 504 without Retry-After: the same query would run as long (D254 ③).
        ErrorStatuses.RETRYABLE.assert().doesNotContain(FrameworkErrors.QUERY_TIMEOUT)
        statuses.problemOf(FrameworkErrors.QUERY_TIMEOUT, "Too long.").status.assert().isEqualTo(504)
    }
}
