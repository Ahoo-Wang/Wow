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
import me.ahoo.wow.api.problem.RetryAdvice
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.http.CART_EMPTY
import me.ahoo.wow.http.CONTEXT
import me.ahoo.wow.http.cartManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import kotlin.time.Duration.Companion.seconds

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
        problem.members().keys.assert().containsExactly("type", "title", "status", "code", "retry")
    }

    @Test
    fun `a rejection's extensions are members of their own names, the problem's own members left to them`() {
        val statuses = ErrorStatuses(cartManifest())
        val extensions =
            mapOf("items" to listOf(mapOf("id" to "i-1")), "code" to "other", "instance" to "/x", "none" to null)
        val result = result(ErrorInfo(CART_EMPTY, "The cart is empty.", extensions = extensions))
        val problem = statuses.problemOf(result)
        // A member's name, or RFC 9457's instance, is no extension of a domain's (#7052).
        problem.extensions.assert().isEqualTo(extensions - "code" - "instance")
        problem.members().let { members ->
            members["items"].assert().isEqualTo(listOf(mapOf("id" to "i-1")))
            members["code"].assert().isEqualTo(CART_EMPTY)
            members.containsKey("instance").assert().isFalse()
            members.containsKey("none").assert().isFalse()
        }
    }

    @Test
    fun `a rejection's problem should carry the version it answered, and no other problem one`() {
        val statuses = ErrorStatuses(cartManifest())
        // With events, the version they reached; without, the one the decision saw: either way the result's.
        val rejected = result(ErrorInfo(CART_EMPTY, "The cart is empty.")).copyWith(version = 3)
        val problem = statuses.problemOf(rejected)
        problem.version.assert().isEqualTo(3)
        problem.members()["version"].assert().isEqualTo(3)
        (problem.members()["result"] as CommandResult).version.assert().isEqualTo(3)
        // A creation's rejection wrote nothing: no version.
        statuses.problemOf(result(ErrorInfo(CART_EMPTY, "The cart is empty."))).members().keys.assert()
            .doesNotContain("version")
        // A failure's version is not a rejection's answer.
        val failed = rejected.copyWith(status = CommandResult.Status.FAILED)
        statuses.problemOf(failed).version.assert().isNull()
        // `version` means the aggregate's version alone: an extension never takes the name, even without one.
        val named = rejected.copyWith(error = ErrorInfo(CART_EMPTY, "Empty.", extensions = mapOf("version" to "v2")))
        statuses.problemOf(named).members()["version"].assert().isEqualTo(3)
        statuses.problemOf(result(ErrorInfo(CART_EMPTY, "Empty.", extensions = mapOf("version" to "v2")))).members()
            .keys.assert().doesNotContain("version")
    }

    /**
     * #7052: an extension named like a member is no member of Wow's, even on a problem without that member; else a
     * client would read a domain's value as Wow's (the TypeScript client takes `violations` as its list of violations).
     */
    @ParameterizedTest
    @ValueSource(
        strings = ["type", "title", "status", "detail", "code", "retry", "violations", "result", "version", "instance"],
    )
    fun `an extension named like a member should be left out, even on a problem without that member`(name: String) {
        val problem = Problem.of(422, "Rejected", null, null, extensions = mapOf(name to "the domain's value"))
        problem.members()[name].assert().isNotEqualTo("the domain's value")
    }

    @Test
    fun `a rejection's extensions should leave out the names Wow keeps, which Wow's own problems write (D484 N7)`() {
        val statuses = ErrorStatuses(cartManifest())
        val extensions = mapOf("requestId" to "r-1", "traceId" to "t-1", "errors" to listOf("e"), "items" to 2)
        val members = statuses.problemOf(result(ErrorInfo(CART_EMPTY, "Empty.", extensions = extensions))).members()
        members.keys.assert().doesNotContain("requestId", "traceId", "errors")
        members["items"].assert().isEqualTo(2)
        // Wow's own problem, CoSec's refusal as Wow writes it, keeps them.
        Problem.of(401, "Unauthorized", null, null, extensions = mapOf("requestId" to "r-1", "traceId" to "t-1"))
            .members().assert().containsEntry("requestId", "r-1").containsEntry("traceId", "t-1")
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
        statuses.title(FrameworkCodes.COMMAND_BUSY, "unknown-context", 429).assert().isEqualTo("Command busy")
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
        val extensions = mapOf("rejectedCode" to FrameworkCodes.COMMAND_BUSY)
        val problem =
            statuses.problemOf(result(ErrorInfo(FrameworkCodes.COMMAND_FAILED, "Refused.", extensions = extensions)))
        problem.status.assert().isEqualTo(500)
        problem.title.assert().isEqualTo("Command failed")
        problem.detail.assert().isEqualTo("Refused.")
        problem.retryAfter.assert().isNull()
        problem.members()["rejectedCode"].assert().isEqualTo(FrameworkCodes.COMMAND_BUSY)
    }

    @Test
    fun `the Manifest's framework table replaces the built-in one`() {
        val statuses =
            ErrorStatuses(Manifest(errors = listOf(ErrorManifest(FrameworkCodes.COMMAND_BUSY, 503, "Busy."))))
        statuses.status(FrameworkCodes.COMMAND_BUSY).assert().isEqualTo(503)
        statuses.title(FrameworkCodes.COMMAND_BUSY, null, 503).assert().isEqualTo("Command busy")
        val problem = statuses.problemOf(FrameworkCodes.COMMAND_BUSY, "Later.")
        problem.assert().isEqualTo(
            Problem(503, "Command busy", "Later.", FrameworkCodes.COMMAND_BUSY, retryAfter = 1.seconds)
        )
    }

    @Test
    fun `a problem a retry may get past carries its retry and Retry-After, which is not a member of the body`() {
        val statuses = ErrorStatuses(Manifest())
        val unavailable = statuses.problemOf(FrameworkCodes.STORAGE_UNAVAILABLE, "Down.")
        unavailable.status.assert().isEqualTo(503)
        unavailable.retry.assert().isEqualTo(RetryAdvice.SAME_REQUEST)
        unavailable.retryAfter.assert().isEqualTo(FrameworkErrors.RETRY_AFTER_SECONDS.seconds)
        unavailable.members().keys.assert().containsExactly("type", "title", "status", "detail", "code", "retry")
        unavailable.members()["retry"].assert().isEqualTo("same-request")
        statuses.problemOf(FrameworkCodes.COMMAND_FAILED, "Failed.").apply {
            retry.assert().isEqualTo(RetryAdvice.NONE)
            retryAfter.assert().isNull()
            members()["retry"].assert().isEqualTo("none")
        }
        // A domain's code is none: no Retry-After is written, whatever is given; an explicit retry wins over the catalog's.
        Problem(422, "Full", null, "shop.cart.full", retryAfter = 5.seconds).retryAfterHeader.assert().isNull()
        Problem(503, "Down", null, FrameworkCodes.COMMAND_FAILED, retry = RetryAdvice.SAME_REQUEST).retryAfter.assert()
            .isEqualTo(FrameworkErrors.RETRY_AFTER_SECONDS.seconds)
    }

    @Test
    fun `every framework code's problem should carry the catalog's retry, and Retry-After exactly when it may be retried`() {
        val statuses = ErrorStatuses(Manifest())
        FrameworkErrors.ALL.forEach { error ->
            val problem = statuses.problemOf(error.code, "Later.")
            problem.retry.assert().describedAs(error.code).isEqualTo(error.retry)
            problem.retryAfter.assert().describedAs(error.code).isEqualTo(error.retryAfter?.seconds)
        }
        // A query that ran out of time is 504 without Retry-After: the same query would run as long (D254 ③).
        statuses.problemOf(FrameworkCodes.QUERY_TIMEOUT, "Too long.").retryAfter.assert().isNull()
        statuses.problemOf(FrameworkCodes.QUERY_TIMEOUT, "Too long.").status.assert().isEqualTo(504)
    }
}
