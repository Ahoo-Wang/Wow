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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.problem.ProblemMapper
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.StorageUnavailableException
import org.springframework.core.codec.CodecException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.server.MissingRequestValueException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * The problem a route answers a failure with that is none of its own (#6510): the one step the application's routes
 * ([GlobalErrors]), Wow's public routes and the query routes share, each keeping before it only what is its own (a
 * service route's refusal, a query's error).
 *
 * 1. What the framework knows: a [ProblemException] as its problem, a [CommandRejectedException] (a command a route
 *    sent through a `CommandClient`) as its result, a [WowProblemException] (another service's answer relayed, or the
 *    application's own) as its code, status, result and retry, a request that fails its constraints (a `@Valid`
 *    body's, a method's) as `wow.request.invalid` with its violations ([RequestViolations]), a request that cannot be read
 *    ([ServerWebInputException]: a body the codecs cannot read, a parameter missing or of the wrong type) as
 *    `wow.request.malformed` naming what does not read, any other [ResponseStatusException] as its status alone, a
 *    store that is unavailable (or a failure caused by one) as 503 `wow.storage.unavailable`, and a body larger than
 *    the codecs take as 413.
 * 2. Then the application's [mappers], in order, for the failure and then each of its causes, but a cause that is
 *    one of the framework's own (the kinds of 1. it answers by themselves, `frameworkOf`): a mapper never sees one,
 *    even wrapped. The first problem one gives is the answer, as the host configures its code's status, once it
 *    passes the checks a mapped problem must ([ProblemMapper]); one that fails them is answered as no mapper's,
 *    logged once by the mapper and code. A mapper that throws is taken as one that does not know the failure, its
 *    failure logged.
 * 3. Else the branch's answer to an unexpected failure: by default a 500 that says nothing of its cause, which is
 *    logged.
 *
 * A mapped code that no context's errors and no service route's errors of the Manifest list (its `catalog`) is
 * answered all the same, and warned of once. What is logged once is remembered for at most [MAX_LOGGED] codes, since
 * a mapper may build its codes from a downstream system's: past them, that is said once, and no more are logged.
 */
@InternalWowApi
class FailureProblems(
    private val statuses: ErrorStatuses,
    private val mappers: List<ProblemMapper> = emptyList(),
    private val catalog: Set<String> = emptySet(),
) {
    private val refused = OnceSet<Pair<String, String>>(MAX_LOGGED) {
        logger.error { "More than $MAX_LOGGED problem mappers' codes were refused: further ones are not logged." }
    }
    private val uncatalogued = OnceSet<String>(MAX_LOGGED) {
        logger.warn { "More than $MAX_LOGGED mapped codes are in no catalog: further ones are not logged." }
    }

    /**
     * The problem [failure] is answered with on a route of [context] (null outside one): what the framework knows,
     * else the first mapper's, else [unexpected]'s.
     */
    fun problemOf(
        failure: Throwable,
        context: String? = null,
        unexpected: (Throwable) -> Problem = ::unexpected,
    ): Problem = known(failure, context) ?: mapped(failure, context) ?: unexpected(failure)

    /**
     * What the framework knows of [failure]: one of its own kinds ([frameworkOf]), or a store that is unavailable or a
     * body too large anywhere among its causes.
     */
    private fun known(failure: Throwable, context: String?): Problem? =
        frameworkOf(failure, context) ?: unavailable(failure, context) ?: tooLargeProblem(failure)

    /**
     * The problem of [failure] when it is one of the framework's own kinds, which it answers by itself, the exception
     * alone and not its causes: the one list of those kinds, which [known] answers and no mapper is offered, even as a
     * cause; null for any other.
     */
    private fun frameworkOf(failure: Throwable, context: String?): Problem? = when (failure) {
        is ProblemException -> failure.problem
        is CommandRejectedException -> statuses.problemOf(failure.result)
        is WowProblemException -> statuses.problemOf(failure, context)
        is WebExchangeBindException -> invalid(RequestViolations.of(failure), context)
        // A return value that fails its method's constraints is the server's own: a 500, its status alone.
        is HandlerMethodValidationException if !failure.isForReturnValue ->
            invalid(RequestViolations.of(failure), context)
        is ServerWebInputException -> tooLargeProblem(failure) ?: malformed(failure, context)
        is ResponseStatusException -> {
            val status = HttpStatus.resolve(failure.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
            Problem.ofStatus(status, failure.reason.takeIf { status.is4xxClientError })
        }
        else -> null
    }

    /**
     * The problem of a request that was read but fails its constraints (#6591): `wow.request.invalid` with its
     * [violations], in the shape of a command's.
     */
    private fun invalid(violations: List<Violation>, context: String?): Problem =
        statuses.problemOf(FrameworkCodes.REQUEST_INVALID, INVALID, context).copyWith(violations = violations)

    /**
     * The problem of a request that cannot be read (#6591): `wow.request.malformed` with Spring's reason, and a
     * violation naming what does not read ([inputOf]) when Spring names it.
     */
    private fun malformed(failure: ServerWebInputException, context: String?): Problem {
        // A ServerWebInputException is always built with its reason.
        val detail = failure.reason!!
        val problem = statuses.problemOf(FrameworkCodes.REQUEST_MALFORMED, detail, context)
        val input = inputOf(failure) ?: return problem
        return problem.copyWith(violations = listOf(Violation(input, FrameworkCodes.REQUEST_MALFORMED, detail)))
    }

    private fun unavailable(failure: Throwable, context: String?): Problem? {
        val unavailable = causes(failure).firstOrNull { it is StorageUnavailableException } ?: return null
        logger.warn(failure) { "A request failed: a store is unavailable." }
        return statuses.problemOf(
            FrameworkCodes.STORAGE_UNAVAILABLE,
            // A StorageUnavailableException always says what is down.
            unavailable.message!!,
            context,
        )
    }

    private fun mapped(failure: Throwable, context: String?): Problem? {
        if (mappers.isEmpty()) return null
        causes(failure).forEach { cause ->
            if (cause !is Exception || cause is CancellationException || frameworkOf(cause, context) != null) {
                return@forEach
            }
            mappers.forEach { mapper ->
                val mapped = ask(mapper, cause, failure)
                if (mapped != null) return answer(mapper, mapped, failure, context)
            }
        }
        return null
    }

    @Suppress("TooGenericExceptionCaught") // A mapper that throws is one that does not know the failure.
    private fun ask(mapper: ProblemMapper, cause: Exception, failure: Throwable): WowProblemException? = try {
        mapper.problemOf(cause)
    } catch (broken: Exception) {
        broken.addSuppressed(failure)
        logger.error(broken) {
            "The problem mapper ${mapper.javaClass.name} failed; the failure is answered without it."
        }
        null
    }

    /** [mapped], the problem [mapper] gave for [failure], as answered; null when it is not one a mapper may give. */
    private fun answer(
        mapper: ProblemMapper,
        mapped: WowProblemException,
        failure: Throwable,
        context: String?,
    ): Problem? {
        val refusal = refusalOf(mapped)
        if (refusal != null) {
            if (refused.first(mapper.javaClass.name to mapped.code)) {
                logger.error(failure) {
                    "The problem mapper ${mapper.javaClass.name} answered ${mapped.code} with status ${mapped.status}, " +
                        "$refusal: the failure is answered as no mapper's."
                }
            }
            return null
        }
        if (mapped.code !in catalog && uncatalogued.first(mapped.code)) {
            logger.warn {
                "The problem mapper ${mapper.javaClass.name} answered ${mapped.code}, which no context's errors and no " +
                    "service route's errors list: declare it among the errors of a context."
            }
        }
        val problem = statuses.problemOf(mapped, context).let { problem ->
            statuses.configured(mapped.code)?.let { problem.copyWith(status = it) } ?: problem
        }
        // The level of the status answered, the host's when it configures one.
        if (problem.status >= SERVER_ERROR) {
            logger.warn(failure) { "A request failed: answered ${mapped.code} by ${mapper.javaClass.name}." }
        } else {
            logger.debug(failure) { "A request failed: answered ${mapped.code} by ${mapper.javaClass.name}." }
        }
        return problem
    }

    @InternalWowApi
    companion object {
        /** The causes a failure is walked through at most: the failure, its cause, and so on. */
        private const val MAX_CAUSES = 16

        /** The codes, or mappers and codes, each set of what is logged once remembers at most. */
        const val MAX_LOGGED: Int = 256
        private const val SERVER_ERROR = 500
        private const val UNAUTHORIZED = 401
        private val ERROR_STATUSES = 400..599
        private val logger = KotlinLogging.logger {}

        /**
         * The step of the routes of [manifest], with the application's [mappers]: a mapped code is looked up among
         * its contexts' errors and its service routes' errors.
         */
        fun of(manifest: Manifest, statuses: ErrorStatuses, mappers: List<ProblemMapper>): FailureProblems {
            val catalog = manifest.contexts.flatMapTo(HashSet()) { context ->
                context.errors.map { it.code } + context.routes.flatMap { it.errors }
            }
            return FailureProblems(statuses, mappers, catalog)
        }

        /** The default answer to an unexpected [failure]: a 500 that says nothing of its cause, which is logged. */
        fun unexpected(failure: Throwable): Problem {
            logger.error(failure) { "A request failed with an unexpected error." }
            return Problem.ofStatus(HttpStatus.INTERNAL_SERVER_ERROR, "The request failed with an unexpected error.")
        }

        /** Why [mapped] is not a problem a mapper may give, or null when it is one. */
        private fun refusalOf(mapped: WowProblemException): String? = when {
            mapped.code.startsWith(FrameworkCodes.PREFIX) -> "a code the framework reserves"
            mapped.status !in ERROR_STATUSES -> "not a 4xx or 5xx status"
            mapped.status == UNAUTHORIZED -> "which only authentication answers"
            mapped.result != null -> "with a command's result, which no mapped failure has"
            else -> null
        }

        /** The detail of a request that fails its constraints. */
        private const val INVALID = "The request failed validation; the violations list what it broke."

        /** The path of the violation of a body that cannot be read. */
        const val BODY: String = "body"

        /**
         * What [failure] could not read: the parameter (a query parameter, a header, a path variable) by its name, or
         * the body ([BODY]) a codec could not read, on an annotated route (`@RequestBody`) or a functional one; null
         * when Spring names neither.
         */
        private fun inputOf(failure: ServerWebInputException): String? {
            if (failure is MissingRequestValueException) return failure.name
            val parameter = failure.methodParameter
            return when {
                parameter != null && !parameter.hasParameterAnnotation(RequestBody::class.java) -> parameter.parameterName
                parameter != null || causes(failure).any { it is CodecException } -> BODY
                else -> null
            }
        }

        private fun causes(failure: Throwable): Sequence<Throwable> {
            val seen = HashSet<Throwable>()
            return generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.take(MAX_CAUSES)
        }
    }
}

/**
 * The keys something was logged once for, at most [limit] of them: [first] is true the first time a key is offered
 * while there is room; once full, [full] runs once, and no key is first any more.
 */
internal class OnceSet<T : Any>(private val limit: Int, private val full: () -> Unit) {
    private val keys: MutableSet<T> = ConcurrentHashMap.newKeySet()
    private val filled = AtomicBoolean()

    /** Whether [key] is offered for the first time, and remembered. */
    fun first(key: T): Boolean {
        if (key in keys) return false
        if (keys.size >= limit) {
            if (filled.compareAndSet(false, true)) full()
            return false
        }
        return keys.add(key)
    }
}
