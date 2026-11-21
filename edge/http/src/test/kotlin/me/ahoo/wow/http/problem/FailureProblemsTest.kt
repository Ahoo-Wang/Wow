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
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.problem.ProblemMapper
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.http.CART_EMPTY
import me.ahoo.wow.http.CONTEXT
import me.ahoo.wow.http.TENANT
import me.ahoo.wow.http.cartManifest
import me.ahoo.wow.http.cartType
import me.ahoo.wow.spi.StorageUnavailableException
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * #6510: the one step the routes take for a failure that is none of their own: what the framework knows, then the
 * application's mappers in order, checked, then the branch's answer to an unexpected failure.
 */
class FailureProblemsTest {
    class UpstreamException(cause: Throwable? = null) : RuntimeException("The catalog did not answer.", cause)

    /** The failures each mapper was asked about, by its name. */
    private val asked = mutableListOf<String>()

    private fun mapper(name: String, answer: (Exception) -> WowProblemException?) = ProblemMapper { failure ->
        asked += "$name:${failure.javaClass.simpleName}"
        answer(failure)
    }

    private fun upstream(
        code: String = UPSTREAM,
        status: Int = 502,
        result: CommandResult? = null,
    ) = mapper(code) { failure ->
        (failure as? UpstreamException)?.let {
            WowProblemException(code, status, Retry.SAME_REQUEST, 3.seconds, "The catalog did not answer.", result)
        }
    }

    private val statuses = ErrorStatuses(cartManifest())

    private fun problems(vararg mappers: ProblemMapper, statuses: ErrorStatuses = this.statuses) =
        FailureProblems.of(cartManifest(), statuses, mappers.toList())

    private val rejection = CommandResult(
        commandId = "c",
        requestId = "r",
        correlationId = "c",
        ref = cartType.ref(TENANT, "c-1"),
        hop = Hop.committed(CONTEXT),
        function = FunctionInfo(FunctionKind.COMMAND, CONTEXT, "cart", "Checkout"),
        status = CommandResult.Status.REJECTED,
        error = ErrorInfo(CART_EMPTY, "The cart is empty."),
        signalTime = 1,
    )

    @Test
    fun `the first mapper that gives a problem answers it, after one that throws and one that does not know it`() {
        val broken = mapper("broken") { error("A defect of the mapper.") }
        val other = mapper("other") { null }
        val problem = problems(broken, other, upstream(), upstream(code = "$CONTEXT.later"))
            .problemOf(UpstreamException())
        problem.status.assert().isEqualTo(502)
        problem.code.assert().isEqualTo(UPSTREAM)
        problem.detail.assert().isEqualTo("The catalog did not answer.")
        problem.retry.assert().isEqualTo(Retry.SAME_REQUEST)
        problem.retryAfterHeader.assert().isEqualTo(3L)
        asked.assert().containsExactly(
            "broken:UpstreamException",
            "other:UpstreamException",
            "$UPSTREAM:UpstreamException",
        )
    }

    @Test
    fun `the mappers are asked of the failure, then of each of its causes`() {
        val problem = problems(upstream()).problemOf(IllegalStateException("Wrapped.", UpstreamException()))
        problem.code.assert().isEqualTo(UPSTREAM)
        asked.assert().containsExactly("$UPSTREAM:IllegalStateException", "$UPSTREAM:UpstreamException")
    }

    @Test
    fun `a cancellation or an error among the causes is never a mapper's`() {
        val everything = mapper("everything") { WowProblemException(CLAIMED, 418, Retry.NONE, null, "Claimed.") }
        problems(everything).problemOf(AssertionError("A JVM error.")).status.assert().isEqualTo(500)
        problems(everything).problemOf(CancellationException("Cancelled.")).status.assert().isEqualTo(500)
        asked.assert().isEmpty()
    }

    @Test
    fun `a mapped problem with a reserved code, 401, a status outside 4xx and 5xx or a result is answered as unmapped`() {
        val refused = listOf(
            upstream(code = FrameworkCodes.COMMAND_BUSY),
            upstream(status = 401),
            upstream(status = 399),
            upstream(status = 600),
            upstream(result = rejection),
        )
        refused.forEach { mapper ->
            // The next mapper is not asked: the failure is no mapper's.
            val problem = problems(mapper, upstream(code = "$CONTEXT.later")).problemOf(UpstreamException())
            problem.status.assert().isEqualTo(500)
            problem.code.assert().isNull()
            problem.detail.assert().isEqualTo("The request failed with an unexpected error.")
        }
        asked.none { it.startsWith("$CONTEXT.later") }.assert().isTrue()
        // Twice the same refusal answers alike.
        problems(upstream(status = 401)).problemOf(UpstreamException()).status.assert().isEqualTo(500)
    }

    @Test
    fun `the status the host configures for a mapped code wins, and a code no catalog lists is answered all the same`() {
        val configured = ErrorStatuses(cartManifest(), mapOf(UPSTREAM to 504))
        problems(upstream(), statuses = configured).problemOf(UpstreamException()).status.assert().isEqualTo(504)
        val listed = problems(upstream(code = CART_EMPTY, status = 409)).problemOf(UpstreamException(), CONTEXT)
        listed.status.assert().isEqualTo(409)
        // A 4xx and a code its context lists: titled by it.
        listed.title.assert().isEqualTo(statuses.title(CART_EMPTY, CONTEXT, 409))
        FailureProblems(statuses, listOf(upstream())).problemOf(UpstreamException()).code.assert().isEqualTo(UPSTREAM)
    }

    @Test
    fun `what the framework knows comes before the mappers`() {
        val everything = mapper("everything") { WowProblemException(CLAIMED, 418, Retry.NONE, null, "Claimed.") }
        val problems = problems(everything)
        problems.problemOf(ProblemException(Problem.ofStatus(HttpStatus.NOT_FOUND))).status.assert().isEqualTo(404)
        problems.problemOf(CommandRejectedException(rejection)).code.assert().isEqualTo(CART_EMPTY)
        problems.problemOf(WowProblemException(IDP_DOWN, 503, Retry.NONE, null, "Down.")).code.assert()
            .isEqualTo(IDP_DOWN)
        val forbidden = problems.problemOf(ResponseStatusException(HttpStatus.FORBIDDEN, "Not yours."))
        forbidden.status.assert().isEqualTo(403)
        forbidden.detail.assert().isEqualTo("Not yours.")
        val unavailable = problems.problemOf(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "secret"))
        unavailable.detail.assert().isNull()
        val wrapped = IllegalStateException("Wrapped.", StorageUnavailableException("The log is down."))
        val down = problems.problemOf(wrapped)
        down.code.assert().isEqualTo(FrameworkCodes.STORAGE_UNAVAILABLE)
        down.detail.assert().isEqualTo("The log is down.")
        problems.problemOf(DataBufferLimitException("Too large.")).status.assert().isEqualTo(413)
        asked.assert().isEmpty()
    }

    @Test
    fun `a failure nothing knows is the branch's own answer, by default a 500 that says nothing`() {
        val failure = IllegalStateException("secret jdbc:postgresql://db-internal:5432")
        val silent = problems().problemOf(failure)
        silent.status.assert().isEqualTo(500)
        silent.code.assert().isNull()
        silent.detail.assert().isEqualTo("The request failed with an unexpected error.")
        val own = problems(upstream()).problemOf(failure, CONTEXT) {
            Problem.ofStatus(HttpStatus.BAD_GATEWAY, it.javaClass.simpleName)
        }
        own.status.assert().isEqualTo(502)
        own.detail.assert().isEqualTo("IllegalStateException")
    }

    private companion object {
        const val UPSTREAM = "$CONTEXT.catalog.unreachable"
        const val CLAIMED = "$CONTEXT.claimed"
        const val IDP_DOWN = "$CONTEXT.idp.down"
    }
}
