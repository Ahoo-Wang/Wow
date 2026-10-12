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

package me.ahoo.wow.http.read

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowPaths
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.command.CommandHandler
import me.ahoo.wow.http.command.Negotiation
import me.ahoo.wow.http.command.RequestHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.Caller
import me.ahoo.wow.runtime.admission.TargetScope
import me.ahoo.wow.runtime.admission.TenantConflictException
import me.ahoo.wow.runtime.admission.TenantPolicy
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The state reads of the public port (decisions D40, D112):
 *
 * - `GET /{context}/{aggregate}/{id}/state`, `?version=` and `?at=` ([handle]): the [StateView] of [reads], with
 *   `ETag: "<version>"`; 304 when `If-None-Match` names that tag. `?at=` takes an ISO-8601 instant (an offset is
 *   read as the instant it names, `+` written `%2B`) and reads the state after the last commit at or before it.
 * - `GET /{context}/{aggregate}/{id}/state/trace?from=&to=&limit=` ([trace]): `{"ref", "commits"}`, the commits from
 *   version `from` (default 1) up to `to` (default the last), at most `limit` of them (default [DEFAULT_TRACE_LIMIT],
 *   at most [MAX_TRACE_LIMIT]: replaying is not free, decision D40), each with its events and the state after it. The
 *   next page starts after the last commit's version.
 *
 * The ref's tenant is the caller's, or the one `Wow-Scope` names for a caller that may act across scopes (403
 * `wow.auth.forbidden` for another), unless [tenants] fixes the aggregate type's tenant (decision D116), as admission
 * does: then it is that tenant, and a `Wow-Scope` that names another is 400 `wow.command.malformed`. A state outside
 * the caller's scope (an owner or a space it names and the state does not have) is 403 `wow.aggregate.forbidden`, as
 * for a command (decision D94); in a trace, every commit's state is checked so, as each `?version=` read would be. A
 * missing state, or a trace with no commit, is 404 `wow.aggregate.not-found`. A deleted aggregate's state is read as
 * any other, `deleted` set. A query parameter that cannot be read is 400 `wow.command.malformed`, the code of a
 * request the edge cannot read, as for a `Wow-Scope` it cannot.
 */
internal class StateHandler(
    private val reads: StateReads,
    private val authenticator: Authenticator,
    private val headers: RequestHeaders,
    private val statuses: ErrorStatuses,
    private val tenants: TenantPolicy = TenantPolicy.CALLERS,
) {
    suspend fun handle(request: ServerRequest, type: AggregateType): ServerResponse {
        val (scope, ref) = reader(request, type)
        val view = read(request, ref)
        checkScope(scope, view.scope, ref)
        val tag = "\"${view.version}\""
        if (request.headers().header(HttpHeaders.IF_NONE_MATCH).any { matches(it, tag) }) {
            return ServerResponse.status(NOT_MODIFIED).eTag(tag).buildAndAwait()
        }
        return ServerResponse.ok().eTag(tag).contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(HttpJson.write(view))
    }

    suspend fun trace(request: ServerRequest, type: AggregateType): ServerResponse {
        val (scope, ref) = reader(request, type)
        val from = request.number(WowPaths.FROM, type, min = 1) ?: 1
        val to = request.number(WowPaths.TO, type, min = from) ?: Int.MAX_VALUE
        val limit = request.number(WowPaths.LIMIT, type, min = 1, max = MAX_TRACE_LIMIT) ?: DEFAULT_TRACE_LIMIT
        val commits = reads.trace(ref, from, to, limit)
        if (commits.isEmpty()) {
            throw problem(FrameworkCodes.NOT_FOUND, "No commit of $ref from version $from.", type)
        }
        commits.forEach { checkScope(scope, it.scope, ref) }
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(HttpJson.write(mapOf("ref" to ref, "commits" to commits)))
    }

    /** Authenticates and negotiates [request]: the scope the caller reads in, and the ref it reads. */
    private suspend fun reader(request: ServerRequest, type: AggregateType): Pair<Scope, AggregateRef> {
        val caller = authenticator.authenticate(request)
        Negotiation.checkAcceptable(request, alsoEventStream = false)
        val target = headers.target(request)
        if (target != null && !caller.crossScope) {
            throw problem(FrameworkCodes.AUTH_FORBIDDEN, "The caller may not read in another scope.", type)
        }
        val scope = target?.scope ?: caller.scope
        return scope to type.ref(tenantOf(type, caller, target), request.pathVariable(CommandHandler.ID))
    }

    private fun checkScope(reader: Scope, state: Scope, ref: AggregateRef) {
        if (!reader.covers(state)) {
            throw problem(FrameworkCodes.AGGREGATE_FORBIDDEN, "$ref is outside the caller's scope.", ref.type)
        }
    }

    /** The tenant [caller] reads an aggregate of [type] in, naming [target] (decision D116). */
    private fun tenantOf(type: AggregateType, caller: Caller, target: TargetScope?): String = try {
        tenants.tenantOf(type, caller, target)
    } catch (conflict: TenantConflictException) {
        throw problem(FrameworkCodes.MALFORMED, conflict.message!!, type)
    }

    /** The state of [ref] the request asks for: the latest, the one at `?version=` or the one as of `?at=`. */
    private suspend fun read(request: ServerRequest, ref: AggregateRef): StateView {
        val version = request.number(WowPaths.VERSION, ref.type, min = 1)
        val at = request.instant(WowPaths.AT, ref.type)
        if (version != null && at != null) {
            throw problem(FrameworkCodes.MALFORMED, "A state is read at a version or at a time, not both.", ref.type)
        }
        val view = when {
            version != null -> reads.at(ref, version)
            at != null -> reads.at(ref, at)
            else -> reads.latest(ref)
        }
        val asked = version?.let { " at version $it" } ?: at?.let { " at $it" }.orEmpty()
        return view ?: throw problem(FrameworkCodes.NOT_FOUND, "No state of $ref$asked.", ref.type)
    }

    /** The ISO-8601 instant of query parameter [name]; null when the request has none. */
    private fun ServerRequest.instant(name: String, type: AggregateType): Instant? {
        val value = queryParam(name).orElse(null) ?: return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            throw problem(FrameworkCodes.MALFORMED, "'$name' is an ISO-8601 instant: '$value'.", type)
        }
    }

    /** The whole number of query parameter [name], from [min] up to [max]; null when the request has none. */
    private fun ServerRequest.number(name: String, type: AggregateType, min: Int, max: Int = Int.MAX_VALUE): Int? {
        val value = queryParam(name).orElse(null) ?: return null
        val range = if (max == Int.MAX_VALUE) "from $min" else "from $min to $max"
        return value.toIntOrNull()?.takeIf { it in min..max }
            ?: throw problem(FrameworkCodes.MALFORMED, "'$name' is a whole number $range: '$value'.", type)
    }

    private fun problem(code: String, detail: String, type: AggregateType) =
        ProblemException(statuses.problemOf(code, detail, type.context))

    companion object {
        /** The commits a trace holds when the request names no `limit`. */
        const val DEFAULT_TRACE_LIMIT = 100

        /** The most commits a trace holds: a larger `limit` is 400. */
        const val MAX_TRACE_LIMIT = 1000

        private const val NOT_MODIFIED = 304

        /** Whether this scope, the reader's, admits a state of [scope]: a field it names, the state has too. */
        private fun Scope.covers(scope: Scope): Boolean =
            (ownerId == null || ownerId == scope.ownerId) && (spaceId == null || spaceId == scope.spaceId)

        private fun matches(ifNoneMatch: String, tag: String): Boolean =
            ifNoneMatch.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == tag }
    }
}
