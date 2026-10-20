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

package me.ahoo.wow.http.ops

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.client.wire.WaitHeader
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.command.Negotiation
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.http.problem.tooLargeProblem
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.node.ParkedKey
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.node.ParkedView
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The operator's routes over a node's parked entries (roadmap P.1, decisions D141, D185), under [OpsPaths.PARKED]:
 *
 * - `GET /wow/ops/parked?subscription=&after=&limit=`: a page of entries, by subscription and aggregate, with the
 *   cursor of the next page (`next`, passed back as `after`);
 * - `GET /wow/ops/parked/{subscription}/{context}/{aggregate}/{tenant}/{id}`: one entry;
 * - `POST …/resume` and `POST …/skip`: Resume and Skip, 200 when this node ran it (the entry `RESOLVED` or still
 *   `PARKED`), 202 when it was left for the owner of the entry's partition (`REQUESTED`, decision D141);
 * - `POST …/mark-recoverable` (`{"recoverable": "RECOVERABLE" | "UNRECOVERABLE" | "UNKNOWN"}`) and
 *   `POST …/apply-retry-spec` (`{"maxRetries": 5, "minBackoff": "1m", "executionTimeout": "2m"}`, each duration
 *   `500ms`, `10s`, `3m`, ISO-8601 or a number of seconds as 9.x took it; a member left out keeps the value the
 *   entry is retried by now): 9.x's `MarkRecoverable` and `ApplyRetrySpec`, answering the entry.
 *
 * An entry that is not parked is a 404. Every route authenticates the caller with [callerResolver] (401 otherwise),
 * and keeps it in its own tenant: a caller that may not act across scopes ([Caller.crossScope]) lists only its
 * tenant's entries, and an entry of another tenant is a 404 to it, as one that is not parked. The four actions are
 * then authorized by [authorizer] as the system commands [OpsCommands] names on the parked aggregate (a
 * [CommandAction] without creation, in the caller's scope), so a CoSec policy guards them as it guards a command, and
 * CoSec's WebFilter can guard the routes by their paths too; without an [authorizer] every action is refused (403
 * `wow.auth.forbidden`), never allowed by default. The reads are authorized the same way, as `wow.ledger.Read`
 * ([OpsPaths.READ]) on each entry's aggregate (review R2.3-9), since an entry shows its failure's message: a listing
 * leaves out the entries the caller may not read, and without an [authorizer] both reads are refused too. Errors are
 * problems as on the other public routes, a store that is unavailable `wow.storage.unavailable` with `Retry-After`
 * (review R2.3-8).
 */
@ExperimentalWowApi
@InternalWowApi
fun parkedRoutes(
    ops: ParkedOps,
    manifest: Manifest,
    authorizer: CommandAuthorizer? = null,
    callerResolver: CallerResolver = CallerResolver.ANONYMOUS,
    options: WowHttpOptions = WowHttpOptions(),
): RouterFunction<ServerResponse> {
    val statuses = ErrorStatuses(manifest, options.errorStatuses)
    val handler = ParkedHandler(ops, Authenticator(callerResolver, statuses), authorizer ?: UNAUTHORIZED, statuses)
    val entry = "${OpsPaths.PARKED}/{$SUBSCRIPTION}/{$CONTEXT}/{$AGGREGATE}/{$TENANT}/{$ID}"
    return coRouter {
        GET(OpsPaths.PARKED) { request -> statuses.answering { handler.list(request) } }
        GET(entry) { request -> statuses.answering { handler.get(request) } }
        POST("$entry/${OpsPaths.RESUME}") { request -> statuses.answering { handler.resume(request) } }
        POST("$entry/${OpsPaths.SKIP}") { request -> statuses.answering { handler.skip(request) } }
        POST("$entry/${OpsPaths.MARK_RECOVERABLE}") { request ->
            statuses.answering { handler.markRecoverable(request) }
        }
        POST("$entry/${OpsPaths.APPLY_RETRY_SPEC}") { request ->
            statuses.answering { handler.applyRetrySpec(request) }
        }
    }
}

/** The paths of the operator's routes, under the reserved context `wow` (decision D104). */
@ExperimentalWowApi
object OpsPaths {
    /** The parked entries; one entry is `{subscription}/{context}/{aggregate}/{tenant}/{id}` under it. */
    const val PARKED: String = "/wow/ops/parked"
    const val RESUME: String = "resume"
    const val SKIP: String = "skip"
    const val MARK_RECOVERABLE: String = "mark-recoverable"
    const val APPLY_RETRY_SPEC: String = "apply-retry-spec"

    /**
     * The system action a read of the entries is authorized as (review R2.3-9), on each entry's aggregate, as the four
     * actions are: a CoSec policy grants it to the operators.
     */
    const val READ: String = "${Ledger.NAMESPACE}.Read"

    /** The query parameters of a listing. */
    const val SUBSCRIPTION: String = "subscription"
    const val AFTER: String = "after"
    const val LIMIT: String = "limit"
}

/**
 * The system commands the operator's actions are authorized as (decision D141): a [CommandAuthorizer] sees a
 * [CommandAction] of one of these types on the parked aggregate.
 */
@ExperimentalWowApi
internal object OpsCommands {
    const val RESUME: String = Ledger.RESUME
    const val SKIP: String = Ledger.SKIP
    const val MARK_RECOVERABLE: String = Ledger.MARK_RECOVERABLE
    const val APPLY_RETRY_SPEC: String = Ledger.APPLY_RETRY_SPEC

    /** The type of a read: no command runs, so its class is the view a read answers. */
    private val READ_TYPE = CommandType(OpsPaths.READ, ParkedView::class.java)

    /**
     * The command type of [name], one of these or [OpsPaths.READ]: the class of its ledger command, as the intakes
     * authorize it.
     */
    fun typeOf(name: String): CommandType =
        if (name == OpsPaths.READ) READ_TYPE else CommandType(name, Ledger.COMMANDS.getValue(name))
}

/** The operator routes' authorizer when the application has none: every action is refused. */
private val UNAUTHORIZED =
    CommandAuthorizer { _, action -> Authorization.Denied(refusedWithoutAuthorizer(action.type.name)) }

private fun refusedWithoutAuthorizer(action: String): String =
    "$action is refused: the service has no CommandAuthorizer for the operator's actions " +
        "(CoSec's, wow.cosec.authorizer.enabled, or the application's own)."

private const val SUBSCRIPTION = "subscription"
private const val CONTEXT = "context"
private const val AGGREGATE = "aggregate"
private const val TENANT = "tenant"
private const val ID = "id"

private class ParkedHandler(
    private val ops: ParkedOps,
    private val authenticator: Authenticator,
    private val authorizer: CommandAuthorizer,
    private val statuses: ErrorStatuses,
) {
    suspend fun list(request: ServerRequest): ServerResponse {
        val caller = authenticate(request)
        val subscription = request.queryParam(OpsPaths.SUBSCRIPTION).orElse(null)?.takeIf { it.isNotBlank() }
        val after = request.queryParam(OpsPaths.AFTER).orElse(null)?.takeIf { it.isNotBlank() }?.let(::decodeCursor)
        val limit = request.queryParam(OpsPaths.LIMIT).orElse(null)?.let { value ->
            value.toIntOrNull()?.takeIf { it in 1..ParkedOps.MAX_LIMIT }
                ?: throw badRequest("limit is a number from 1 to ${ParkedOps.MAX_LIMIT}: '$value'.")
        } ?: ParkedOps.DEFAULT_LIMIT
        if (authorizer === UNAUTHORIZED) {
            throw ProblemException(
                statuses.problemOf(FrameworkCodes.AUTH_FORBIDDEN, refusedWithoutAuthorizer(OpsPaths.READ))
            )
        }
        val page = ops.list(subscription, after, limit) { caller.sees(it) }
        val readable = page.entries.filter { view -> readable(caller, view.entry.ref) }
        return ok(
            mapOf(
                "entries" to readable.map(::viewOf),
                "next" to page.next?.let(::encodeCursor),
            ),
        )
    }

    suspend fun get(request: ServerRequest): ServerResponse {
        val (subscription, ref) = authorized(request, OpsPaths.READ)
        return ok(viewOf(ops.get(subscription, ref) ?: throw notParked(subscription, ref)))
    }

    /** Whether [caller] may read the entry of [ref]. */
    private suspend fun readable(caller: Caller, ref: AggregateRef): Boolean =
        authorizer.authorize(caller, actionOf(caller, OpsPaths.READ, ref)) is Authorization.Allowed

    suspend fun resume(request: ServerRequest): ServerResponse =
        act(request, OpsCommands.RESUME) { subscription, ref -> ops.resume(subscription, ref) }

    suspend fun skip(request: ServerRequest): ServerResponse =
        act(request, OpsCommands.SKIP) { subscription, ref -> ops.skip(subscription, ref) }

    private suspend fun act(
        request: ServerRequest,
        command: String,
        action: suspend (String, AggregateRef) -> ParkedOps.Outcome,
    ): ServerResponse {
        val (subscription, ref) = authorized(request, command)
        val outcome = action(subscription, ref)
        if (outcome == ParkedOps.Outcome.NOT_PARKED) {
            throw notParked(subscription, ref)
        }
        val status = if (outcome == ParkedOps.Outcome.REQUESTED) HttpStatus.ACCEPTED else HttpStatus.OK
        val body = buildMap {
            put("outcome", outcome.name)
            ops.get(subscription, ref)?.let { put("entry", viewOf(it)) }
        }
        return respond(status, body)
    }

    suspend fun markRecoverable(request: ServerRequest): ServerResponse {
        val (subscription, ref) = authorized(request, OpsCommands.MARK_RECOVERABLE)
        val recoverable = recoverableOf(body(request))
        val view = ops.markRecoverable(subscription, ref, recoverable) ?: throw notParked(subscription, ref)
        return ok(viewOf(view))
    }

    private fun recoverableOf(body: JsonNode): Recoverable {
        val value = body[RECOVERABLE]?.takeIf { it.isString }?.asString()
            ?: throw badRequest("The body names the entry's \"recoverable\": ${Recoverable.entries.joinToString()}.")
        return Recoverable.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw badRequest("Not a recoverable: '$value'; one of ${Recoverable.entries.joinToString()}.")
    }

    suspend fun applyRetrySpec(request: ServerRequest): ServerResponse {
        val (subscription, ref) = authorized(request, OpsCommands.APPLY_RETRY_SPEC)
        val body = body(request)
        val current = ops.get(subscription, ref)?.spec ?: throw notParked(subscription, ref)
        val view = ops.applyRetrySpec(subscription, ref, specOf(body, current)) ?: throw notParked(subscription, ref)
        return ok(viewOf(view))
    }

    /** The spec [body] gives, each member it leaves out [current]'s. */
    private fun specOf(body: JsonNode, current: ParkedRetrySpec): ParkedRetrySpec {
        val maxRetries = body[MAX_RETRIES]?.let { node ->
            node.takeIf { it.isInt }?.asInt() ?: throw badRequest("maxRetries is a whole number.")
        }
        return try {
            ParkedRetrySpec(
                maxRetries = maxRetries ?: current.maxRetries,
                minBackoff = body[MIN_BACKOFF]?.let { durationOf(MIN_BACKOFF, it) } ?: current.minBackoff,
                executionTimeout = body[EXECUTION_TIMEOUT]?.let { durationOf(EXECUTION_TIMEOUT, it) }
                    ?: current.executionTimeout,
            )
        } catch (invalid: IllegalArgumentException) {
            throw badRequest(invalid.message ?: "Not a retry spec.")
        }
    }

    private suspend fun authenticate(request: ServerRequest): Caller {
        val caller = authenticator.authenticate(request)
        Negotiation.checkAcceptable(request, alsoEventStream = false)
        return caller
    }

    /** The entry [request] names, once [command] on its aggregate is authorized for the caller. */
    private suspend fun authorized(request: ServerRequest, command: String): Pair<String, AggregateRef> {
        val caller = authenticate(request)
        val target = target(request, caller)
        authorize(caller, command, target.second)
        return target
    }

    /** Refuses [command] on [ref] unless [authorizer] allows it to [caller]. */
    private suspend fun authorize(caller: Caller, command: String, ref: AggregateRef) {
        val authorization = authorizer.authorize(caller, actionOf(caller, command, ref))
        if (authorization is Authorization.Denied) {
            throw ProblemException(statuses.problemOf(authorization.code, authorization.message))
        }
    }

    private fun actionOf(caller: Caller, command: String, ref: AggregateRef): CommandAction =
        CommandAction(OpsCommands.typeOf(command), ref, caller.scope, Creation.NONE)

    /** The entry [request] names: one of [caller]'s tenant, else a 404 as for an entry that is not parked. */
    private fun target(request: ServerRequest, caller: Caller): Pair<String, AggregateRef> {
        val target = try {
            request.pathVariable(SUBSCRIPTION) to
                AggregateType(request.pathVariable(CONTEXT), request.pathVariable(AGGREGATE))
                    .ref(request.pathVariable(TENANT), request.pathVariable(ID))
        } catch (invalid: IllegalArgumentException) {
            throw badRequest(invalid.message ?: "Not a parked entry's path.")
        }
        if (!caller.sees(target.second)) {
            throw notParked(target.first, target.second)
        }
        return target
    }

    /** Whether this caller sees the entry of [ref]: one of its own tenant, or any for a cross-scope caller. */
    private fun Caller.sees(ref: AggregateRef): Boolean = crossScope || ref.tenantId == tenantId

    private suspend fun body(request: ServerRequest): JsonNode {
        Negotiation.checkJsonBody(request, "The body")
        val bytes = request.awaitBodyOrNull<ByteArray>()?.takeIf { it.isNotEmpty() } ?: return EMPTY
        val node = try {
            HttpJson.mapper.readTree(bytes)
        } catch (malformed: JacksonException) {
            throw badRequest("The body is not JSON: ${malformed.originalMessage}")
        }
        if (!node.isObject) {
            throw badRequest("The body is a JSON object.")
        }
        return node
    }

    private fun durationOf(name: String, node: JsonNode): Duration = when {
        node.isIntegralNumber && node.asLong() >= 0 -> node.asLong().seconds
        node.isString -> node.asString().trim().let { text ->
            if (ZERO.matches(text)) Duration.ZERO else runCatching { WaitHeader.parseTimeout(text) }.getOrNull()
        }
        else -> null
    } ?: throw badRequest("$name is a duration (500ms, 10s, 3m, ISO-8601) or a number of seconds.")

    private fun notParked(subscription: String, ref: AggregateRef) = ProblemException(
        Problem.ofStatus(HttpStatus.NOT_FOUND, "$subscription has not parked $ref."),
    )

    private fun badRequest(detail: String) = ProblemException(
        statuses.problemOf(FrameworkCodes.MALFORMED, detail),
    )

    private suspend fun ok(body: Any): ServerResponse = respond(HttpStatus.OK, body)

    private suspend fun respond(status: HttpStatus, body: Any): ServerResponse =
        ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(HttpJson.write(body))

    private companion object {
        const val RECOVERABLE = "recoverable"
        const val MAX_RETRIES = "maxRetries"
        const val MIN_BACKOFF = "minBackoff"
        const val EXECUTION_TIMEOUT = "executionTimeout"
        val EMPTY: JsonNode = HttpJson.mapper.createObjectNode()
        val ZERO = Regex("""^0+\s*(ms|s|m)?$|^PT0+(\.0+)?S$""", RegexOption.IGNORE_CASE)
    }
}

/** A parked entry as the routes write it. */
internal fun viewOf(view: ParkedView): Map<String, Any?> {
    val entry = view.entry
    return linkedMapOf(
        "subscription" to entry.subscription,
        "aggregate" to mapOf(
            "context" to entry.ref.type.context,
            "name" to entry.ref.type.name,
            "tenantId" to entry.ref.tenantId,
            "id" to entry.ref.id,
        ),
        "fromVersion" to entry.fromVersion,
        "fromPosition" to entry.fromPosition,
        "reason" to entry.reason,
        "failedAt" to entry.failedAt,
        "retries" to entry.retries,
        "retryAt" to entry.retryAt,
        "retrySpec" to mapOf(
            "maxRetries" to view.spec.maxRetries,
            "minBackoff" to view.spec.minBackoff.toIsoString(),
            "executionTimeout" to view.spec.executionTimeout.toIsoString(),
            "applied" to (entry.retrySpec != null),
        ),
        "recoverable" to entry.recoverable.name,
        "request" to entry.request?.name,
        "status" to view.status.name,
        "nextRetryAt" to view.nextRetryAt,
    )
}

/**
 * A listing's cursor: the key of the last entry of a page, as URL-safe Base64 of its subscription and aggregate
 * separated by NUL, which no name or id holds.
 */
internal fun encodeCursor(key: ParkedKey): String {
    val ref = key.ref
    val parts = listOf(key.subscription, ref.type.context, ref.type.name, ref.tenantId, ref.id)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(parts.joinToString(SEPARATOR).toByteArray())
}

private fun decodeCursor(cursor: String): ParkedKey {
    val parts = try {
        String(Base64.getUrlDecoder().decode(cursor)).split(SEPARATOR)
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
    val key = parts.takeIf { it.size == CURSOR_PARTS }?.let { part ->
        runCatching { ParkedKey(part[0], AggregateType(part[1], part[2]).ref(part[3], part[4])) }.getOrNull()
    }
    return key ?: throw ProblemException(Problem.ofStatus(HttpStatus.BAD_REQUEST, "Not a cursor of this listing."))
}

private const val SEPARATOR = "\u0000"
private const val CURSOR_PARTS = 5

/**
 * [block]'s response, or the problem it was answered with (review R2.3-8), as on Wow's other routes: a store that is
 * unavailable is `wow.storage.unavailable` with `Retry-After`, a body larger than the codecs take 413, anything else a
 * 500 problem that says nothing of its cause, which is logged.
 */
@Suppress("TooGenericExceptionCaught") // Whatever else a route throws is answered as a problem, not a bare 500.
private suspend inline fun ErrorStatuses.answering(block: () -> ServerResponse): ServerResponse = try {
    block()
} catch (problem: ProblemException) {
    problem.problem.respond()
} catch (unavailable: StorageUnavailableException) {
    logger.warn(unavailable) { "An operator's request failed: a store is unavailable." }
    problemOf(FrameworkErrors.STORAGE_UNAVAILABLE, unavailable.message ?: "A store is unavailable.").respond()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    if (tooLargeProblem(failure) == null) {
        logger.error(failure) { "An operator's request failed with an unexpected error." }
    }
    Problem.ofFailure(failure).respond()
}

private val logger = KotlinLogging.logger {}
