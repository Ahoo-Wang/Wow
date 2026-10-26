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

package me.ahoo.wow.http.command

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.wire.ScopeHeader
import me.ahoo.wow.api.wire.WaitHeader
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import kotlin.time.Duration

/**
 * Reads the Wow headers of a request (decisions D95, D98, D99); a header that does not parse is answered as a problem
 * before the command has a ref: `wow.wait.invalid` for the wait, `wow.command.malformed` for the others.
 *
 * [publicPort] is whether the requests come to the public port, the one port whose commands carry the client's address
 * and `User-Agent` ([facts]), unless [clientFacts] is off (decision D267 ⑯): on the internal port the peer is a service
 * of the cluster, not the client.
 */
internal class RequestHeaders(
    private val statuses: ErrorStatuses,
    private val publicPort: Boolean = false,
    private val clientFacts: Boolean = true,
) {
    /**
     * The wait plan of `Wow-Wait` and `Wow-Wait-Timeout`; null when neither is given (the default plan, D69, within the
     * node's default timeout). A plan given without a timeout waits [defaultTimeout], the node's.
     */
    fun wait(request: ServerRequest, defaultTimeout: Duration): WaitPlan? {
        val plan = request.header(WowHeaders.WAIT)
        val timeout = request.header(WowHeaders.WAIT_TIMEOUT)
        if (plan == null && timeout == null) {
            return null
        }
        return try {
            val parsed = plan?.let(WaitHeader::parse) ?: WaitPlan.DEFAULT
            parsed.within(timeout?.let(WaitHeader::parseTimeout) ?: defaultTimeout)
        } catch (invalid: IllegalArgumentException) {
            throw problem(FrameworkCodes.WAIT_INVALID, invalid.message ?: "The wait does not parse.")
        }
    }

    /**
     * The target scope of `Wow-Scope`, or null without it; one that does not parse, or names several tenants (a query
     * route's alone, [queryTarget]), is a problem with [malformed].
     */
    fun target(request: ServerRequest, malformed: String = FrameworkCodes.COMMAND_MALFORMED): TargetScope? =
        scope(request, malformed, ScopeHeader::parse)

    /**
     * What `Wow-Scope` names on a query route (decision D254 ⑦), or null without it: a target scope, or the tenants of a
     * cross-tenant query; one that does not parse is a problem with [malformed].
     */
    fun queryTarget(request: ServerRequest, malformed: String): ScopeHeader.QueryTarget? =
        scope(request, malformed, ScopeHeader::parseQuery)

    private fun <T> scope(request: ServerRequest, malformed: String, parse: (String) -> T): T? =
        request.header(WowHeaders.SCOPE)?.let {
            try {
                parse(it)
            } catch (invalid: IllegalArgumentException) {
                throw problem(malformed, invalid.message ?: "Wow-Scope does not parse.")
            }
        }

    /**
     * The expected version of `If-Match`: one entity tag, the version (`"3"`, or `3`), or null without it or for
     * `If-Match: *`, which expects no version in particular.
     */
    fun expectedVersion(request: ServerRequest): Int? {
        val value = request.header(WowHeaders.IF_MATCH) ?: return null
        if (value == "*") {
            return null
        }
        val tag = value.removeSurrounding("\"")
        return tag.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw problem(FrameworkCodes.COMMAND_MALFORMED, "If-Match is one version as an entity tag, such as \"3\".")
    }

    /**
     * The synchronous call of `Wow-Call` (decision D193). On the public port ([internal] false) a request that carries
     * it is answered 403 `wow.auth.forbidden` whatever its credential: a call is taken on the internal port alone
     * (decision D173). On the internal port every request is a call: one without it, or from a caller that is not a
     * [service][Caller.service] identity, is answered 403 too. One that does not parse is a problem with
     * [FrameworkCodes.COMMAND_MALFORMED]; null on the public port without it.
     *
     * A call's wait context goes on only when its origin is a node of the calling service (review R2.3-1): the target
     * sends the wait's signals, with a credential, to the origin's address, so a caller must not point them at another
     * service's nodes, this one's among them, or at an address of its choosing in their name. A wait of another
     * origin is dropped, logged: the command runs, and the waiter, if any, times out instead of seeing its signals.
     */
    fun call(request: ServerRequest, caller: Caller, internal: Boolean): CallContext? {
        val value = request.header(WowHeaders.CALL)
        val refusal = when {
            !internal && value != null ->
                "${WowHeaders.CALL} is taken on the internal port alone, never on the public one."

            !internal -> return null
            value == null || !caller.service ->
                "The internal port takes only a service identity's call with ${WowHeaders.CALL}."

            else -> null
        }
        refusal?.let { throw problem(FrameworkCodes.AUTH_FORBIDDEN, it) }
        val call = parseCall(checkNotNull(value))
        val wait = call.wait ?: return call
        val service = CallPermits.serviceOf(caller)
        if (wait.origin.service == service) {
            return call
        }
        logger.warn {
            "Dropped the wait ${wait.waitId} of call ${call.commandId} from service $service: its origin is a node " +
                "of service ${wait.origin.service}, not of the caller's."
        }
        return CallContext.of(
            call.commandId,
            call.tenantId,
            call.scope,
            call.causation,
            call.operator,
            facts = call.facts
        )
    }

    private fun parseCall(value: String): CallContext = try {
        CallHeaders.parse(value)
    } catch (invalid: IllegalArgumentException) {
        throw problem(FrameworkCodes.COMMAND_MALFORMED, invalid.message ?: "${WowHeaders.CALL} does not parse.")
    }

    /** The request id of `Wow-Request-Id`, or null without it. */
    fun requestId(request: ServerRequest): String? = request.header(WowHeaders.REQUEST_ID)

    /**
     * The request facts [names] lists that [request] carries, as named in [names], and the standard request facts
     * ([RequestFacts]) the framework fills itself:
     *
     * - [RequestFacts.APP_ID], the [app][Caller.app] of the [caller] its resolver authenticated (decision D232), on
     *   either port: on the internal port the calling service's own, which admission replaces with the root
     *   request's, the facts its `Wow-Call` carries (decision D236 ③); none for a caller without one;
     * - on the public port (09 §17 G-10), as 9.x's header appenders wrote them, [RequestFacts.USER_AGENT], the
     *   request's `User-Agent`, when it has one, and [RequestFacts.REMOTE_IP], the address literal of the request's
     *   remote address (never a host name, which a reverse DNS lookup would block the event loop for; an unresolved
     *   address as it was given), when the server knows it.
     *
     * The remote address is the server's: the connection's peer or, when the host trusts a proxy in front of it, the
     * client that proxy forwarded for. `X-Forwarded-For` and `Forwarded` are never read here: 9.x took the first
     * `X-Forwarded-For` entry as it came, which any client can forge. Trusting a proxy is the host's decision, made
     * where its server is configured: Spring Boot's `server.forward-headers-strategy` (`native` or `framework`)
     * rewrites the remote address from the forwarded headers before the routes see the request; the Wow host makes it
     * `framework` unless the application sets it (decision D303).
     *
     * Each fact is cut to [MAX_FACT_LENGTH] characters (review S-10): a fact lands in the immutable log, so no client
     * makes an entry grow with a header of its choosing.
     *
     * No request sets a standard fact itself (the trust boundary of decision D226): a listed name that is one of them,
     * in any case, is not copied, on either port, and on the internal port, whose peer is a service of the cluster,
     * neither the address nor the `User-Agent` is recorded. A command forwarded to its owner over the cluster channel
     * keeps the facts the edge recorded.
     */
    fun facts(request: ServerRequest, names: Set<String>, caller: Caller): Map<String, String> {
        val listed = names.filter { it.lowercase() !in RequestFacts.STANDARD }
            .mapNotNull { name -> request.header(name)?.let { name to it } }
            .toMap()
        val standard = buildMap {
            caller.app?.let { put(RequestFacts.APP_ID, it.trim()) }
            if (publicPort && clientFacts) {
                request.header(HttpHeaders.USER_AGENT)?.takeIf { it.isNotEmpty() }
                    ?.let { put(RequestFacts.USER_AGENT, it) }
                request.remoteAddress().ifPresent { remote ->
                    put(RequestFacts.REMOTE_IP, remote.address?.hostAddress ?: remote.hostString)
                }
            }
        }
        return (listed + standard).mapValues { (_, value) -> value.take(MAX_FACT_LENGTH) }
    }

    private fun problem(code: String, detail: String) = ProblemException(statuses.problemOf(code, detail))

    private fun ServerRequest.header(name: String): String? = headers().firstHeader(name)?.trim()

    companion object {
        private val logger = KotlinLogging.logger {}

        /** The most characters of one request fact a command records (review S-10); the rest is cut off. */
        const val MAX_FACT_LENGTH: Int = 512
    }
}

/** The media types a public route answers in. */
internal object Negotiation {
    private val ANSWERED = listOf(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)

    /** Whether the client asked for server-sent events. */
    fun wantsEventStream(request: ServerRequest): Boolean =
        accept(request).any { it.equalsTypeAndSubtype(MediaType.TEXT_EVENT_STREAM) }

    /** Fails with 406 when the client accepts none of the JSON types [alsoEventStream] or event stream. */
    fun checkAcceptable(request: ServerRequest, alsoEventStream: Boolean) {
        val accept = accept(request)
        val answered = if (alsoEventStream) ANSWERED + MediaType.TEXT_EVENT_STREAM else ANSWERED
        if (accept.isNotEmpty() && accept.none { type -> answered.any { type.isCompatibleWith(it) } }) {
            throw ProblemException(
                Problem.ofStatus(HttpStatus.NOT_ACCEPTABLE, "The route answers in ${answered.joinToString()}."),
            )
        }
    }

    /** Fails with 415 when the request body is given as another type than JSON; [what] names what the body is. */
    fun checkJsonBody(request: ServerRequest, what: String = "A command") {
        val type = try {
            request.headers().contentType().orElse(null)
        } catch (_: InvalidMediaTypeException) {
            throw ProblemException(Problem.ofStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type does not parse."))
        } ?: return
        if (!type.isCompatibleWith(MediaType.APPLICATION_JSON) && !type.subtype.endsWith("+json")) {
            throw ProblemException(
                Problem.ofStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "$what is sent as application/json."),
            )
        }
    }

    private fun accept(request: ServerRequest): List<MediaType> = try {
        request.headers().accept()
    } catch (_: InvalidMediaTypeException) {
        throw ProblemException(Problem.ofStatus(HttpStatus.NOT_ACCEPTABLE, "Accept does not parse."))
    }
}
