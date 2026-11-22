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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.http.CART_EMPTY
import me.ahoo.wow.http.CONTEXT
import me.ahoo.wow.http.TENANT
import me.ahoo.wow.http.cartManifest
import me.ahoo.wow.http.cartType
import me.ahoo.wow.spi.StorageUnavailableException
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.MethodParameter
import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpStatus
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.MissingRequestValueException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException
import java.util.concurrent.CompletionException
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
        // The same refusal again answers alike, logged once.
        val unauthorized = problems(upstream(status = 401))
        repeat(2) { unauthorized.problemOf(UpstreamException()).status.assert().isEqualTo(500) }
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
    fun `a failure of the framework's own is never offered to a mapper, even as the cause of another`() {
        // Claims whatever it is asked about but the wrapper an async boundary puts around a failure.
        val claiming = mapper("claiming") { failure ->
            val claimed = WowProblemException(CLAIMED, 418, Retry.NONE, null, "Claimed.")
            claimed.takeUnless { failure is CompletionException }
        }
        val problems = problems(claiming)
        val unauthenticated = ProblemException(
            statuses.problemOf(FrameworkCodes.AUTH_UNAUTHENTICATED, "No credentials accepted here."),
        )
        val wrapped = listOf(
            unauthenticated,
            WowProblemException(IDP_DOWN, 503, Retry.NONE, null, "Down."),
            CommandRejectedException(rejection),
        ).map { CompletionException(it) }
        wrapped.forEach { failure ->
            // Answered as today, as no mapper's: a 500 that says nothing.
            val problem = problems.problemOf(failure)
            problem.status.assert().isEqualTo(500)
            problem.code.assert().isNull()
        }
        asked.assert().containsOnly("claiming:CompletionException")
        asked.size.assert().isEqualTo(3)
    }

    @Test
    fun `the application's own exception that wraps a Wow problem is mapped by the outer one`() {
        val problem = problems(upstream()).problemOf(
            UpstreamException(WowProblemException(IDP_DOWN, 503, Retry.NONE, null, "Down.")),
        )
        problem.code.assert().isEqualTo(UPSTREAM)
        problem.status.assert().isEqualTo(502)
        asked.assert().containsExactly("$UPSTREAM:UpstreamException")
    }

    @Test
    fun `what is logged once is remembered for a bounded number of codes, the bound said once`() {
        val events = logged {
            // A mapper that builds its codes from a downstream system's.
            val downstream = mapper("downstream") { failure ->
                WowProblemException("pay.${failure.message}", 502, Retry.NONE, null, "The payment failed.")
            }
            val problems = problems(downstream)
            repeat(FailureProblems.MAX_LOGGED + 10) { problems.problemOf(IllegalStateException("code-$it")) }
            // Logged once already: not again.
            problems.problemOf(IllegalStateException("code-0"))
            // And one whose codes the framework reserves, each refused.
            val reserving = mapper("reserved") { failure ->
                WowProblemException("wow.${failure.message}", 502, Retry.NONE, null, "No.")
            }
            val reserved = problems(reserving)
            repeat(FailureProblems.MAX_LOGGED + 10) { reserved.problemOf(IllegalStateException("code-$it")) }
        }
        events.count { it.level == Level.WARN && "no context's errors" in it.formattedMessage }.assert()
            .isEqualTo(FailureProblems.MAX_LOGGED)
        events.count { it.level == Level.ERROR && "a code the framework reserves" in it.formattedMessage }.assert()
            .isEqualTo(FailureProblems.MAX_LOGGED)
        events.count { "further ones are not logged" in it.formattedMessage }.assert().isEqualTo(2)
    }

    @Test
    fun `a mapped problem is logged at the level of the status answered, the host's when it configures one`() {
        val configured = ErrorStatuses(cartManifest(), mapOf(UPSTREAM to 404, CART_EMPTY to 503))
        val events = logged {
            problems(upstream(), statuses = configured).problemOf(UpstreamException())
            problems(upstream(code = CART_EMPTY, status = 409), statuses = configured).problemOf(UpstreamException())
        }
        val answered = events.filter { "answered" in it.formattedMessage && "A request failed" in it.formattedMessage }
        answered.single { UPSTREAM in it.formattedMessage }.level.assert().isEqualTo(Level.DEBUG)
        answered.single { CART_EMPTY in it.formattedMessage }.level.assert().isEqualTo(Level.WARN)
    }

    /** The events [FailureProblems] logs while [block] runs, at debug and above. */
    private fun logged(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(FailureProblems::class.java.name) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val level = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
        return appender.list
    }

    @Test
    fun `a request that cannot be read is wow-request-malformed naming the parameter or the body (#6591)`() {
        val everything = mapper("everything") { WowProblemException(CLAIMED, 418, Retry.NONE, null, "Claimed.") }
        val problems = problems(everything)
        // A header is named as the request names it, not as the parameter that reads it.
        val missing = MissingRequestValueException("X-Tenant", String::class.java, "header", parameter(1))
        problems.problemOf(missing).assertMalformed("X-Tenant", "Required header 'X-Tenant' is not present.")
        val mistyped = ServerWebInputException("Type mismatch.", parameter(1), IllegalArgumentException())
        problems.problemOf(mistyped).assertMalformed("n", "Type mismatch.")
        val annotatedBody = ServerWebInputException("Failed to read HTTP message", parameter(0))
        problems.problemOf(annotatedBody).assertMalformed(FailureProblems.BODY, "Failed to read HTTP message")
        val functionalBody = unnamedInput("Failed to read HTTP message", DecodingException("Bad JSON."))
        problems.problemOf(functionalBody).assertMalformed(FailureProblems.BODY, "Failed to read HTTP message")
        // A mapper is never offered one, not even wrapped.
        problems.problemOf(IllegalStateException("Wrapped.", mistyped)).status.assert().isEqualTo(418)
        asked.assert().containsExactly("everything:IllegalStateException")
    }

    @Test
    fun `a request that cannot be read and names nothing is wow-request-malformed without a violation`() {
        // A parameter of a class compiled without its names (the JDK's): Spring cannot name it.
        val substring = String::class.java.getMethod("substring", Int::class.javaPrimitiveType)
        val unnamedParameter = MethodParameter(substring, 0)
        val unnamed = problems().problemOf(ServerWebInputException("Type mismatch.", unnamedParameter))
        unnamed.code.assert().isEqualTo(FrameworkCodes.REQUEST_MALFORMED)
        unnamed.violations.assert().isEmpty()
        val bare = problems().problemOf(ServerWebInputException("Not this way."))
        bare.code.assert().isEqualTo(FrameworkCodes.REQUEST_MALFORMED)
        bare.detail.assert().isEqualTo("Not this way.")
        bare.violations.assert().isEmpty()
    }

    @Test
    fun `a body too large to read is 413, and a body that fails @Valid is its status alone`() {
        val tooLarge = unnamedInput("Failed to read HTTP message", DataBufferLimitException("Big."))
        problems().problemOf(tooLarge).status.assert().isEqualTo(413)
        val binding = WebExchangeBindException(parameter(0), BeanPropertyBindingResult(Any(), "item"))
        val invalid = problems().problemOf(binding)
        invalid.status.assert().isEqualTo(400)
        invalid.code.assert().isNull()
        invalid.detail.assert().isEqualTo("Validation failure")
    }

    private fun Problem.assertMalformed(path: String, detail: String) {
        status.assert().isEqualTo(400)
        code.assert().isEqualTo(FrameworkCodes.REQUEST_MALFORMED)
        this.detail.assert().isEqualTo(detail)
        violations.assert().containsExactly(Violation.of(path, FrameworkCodes.REQUEST_MALFORMED, detail))
    }

    /** A request that cannot be read with no parameter, as a functional route's body decoding gives one. */
    private fun unnamedInput(reason: String, cause: Throwable): ServerWebInputException =
        ServerWebInputException::class.java
            .getConstructor(String::class.java, MethodParameter::class.java, Throwable::class.java)
            .newInstance(reason, null, cause)

    /** The [index]th parameter of [Routes.route]: its body `item`, then `n`, its name discovered as Spring does. */
    private fun parameter(index: Int): MethodParameter {
        val method = Routes::class.java.getMethod("route", String::class.java, Int::class.javaPrimitiveType)
        return MethodParameter(method, index).apply { initParameterNameDiscovery(DefaultParameterNameDiscoverer()) }
    }

    /** An annotated route: its body, and a parameter. */
    class Routes {
        @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
        fun route(@RequestBody item: String, @RequestParam n: Int): String = "routed"
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
