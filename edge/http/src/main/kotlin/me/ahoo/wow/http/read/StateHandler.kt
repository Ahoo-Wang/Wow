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
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.read.MinVersionNotReachedException
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.http.EntityTags
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.WowPaths
import me.ahoo.wow.http.caller.Authenticator
import me.ahoo.wow.http.command.CommandHandler
import me.ahoo.wow.http.command.Negotiation
import me.ahoo.wow.http.command.RequestHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.admission.QueryTargets
import me.ahoo.wow.query.engine.ReadFacade
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.query.state.InvalidStateReadException
import me.ahoo.wow.query.state.StateNotFoundException
import me.ahoo.wow.query.state.StateNotFoundException.Reason
import me.ahoo.wow.query.state.StateRead
import me.ahoo.wow.runtime.state.CommitView
import me.ahoo.wow.runtime.state.StateReadView
import me.ahoo.wow.runtime.state.StateReads
import me.ahoo.wow.runtime.state.StateReplayLimitException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The state reads of the public port, over the node's [StateReads] (`:runtime`):
 *
 * - `GET /{context}/{aggregate}/{id}/state`, `?version=` and `?at=` ([handle]): the [StateReadView] of [reads], with
 *   `ETag: "<version>-<digest>"` ([EntityTags]), the digest a hash of the body as the caller reads it, so a state
 *   whose version stays while what is read of it changes (a state revision rebuilt; an upcaster; the
 *   masks) is tagged anew; 304 when `If-None-Match` names that tag. The latest state is the snapshot, else, before the
 *   aggregate's first snapshot, its log replayed, each read alike. `?at=` takes an ISO-8601 instant
 *   (an offset is read as the instant it names, `+` written `%2B`) and reads the state after the last commit at or
 *   before it. `?minVersion=` (read your writes) reads the latest state at that version or later,
 *   a lagging snapshot caught up with the log ([StateReads.latest] of a version); a state the caller may see that
 *   stays below it through the read's wait is 409 `wow.query.min-version-not-reached`, the problem's `version` its
 *   current version, with `Retry-After`. It reads no version or time besides (400).
 * - `GET /{context}/{aggregate}/{id}/state/trace?from=&to=&limit=` ([trace]): `{"ref", "commits"}`, the commits from
 *   version `from` (default 1) up to `to` (default the last), at most `limit` of them (default [DEFAULT_TRACE_LIMIT],
 *   at most [MAX_TRACE_LIMIT]: replaying is not free), each with its events and the state after it. The
 *   next page starts after the last commit's version.
 *
 * A read that replays the log (a state at a version or a time, a trace, the latest state before the first snapshot)
 * runs under the replay gate of [reads], the query bulkhead of the aggregate's log store (429 `wow.query.busy` when it
 * is full), and replays a bounded number of commits: a state further away is 400 `wow.query.invalid` with the
 * violation `REPLAY_TOO_LONG` on the parameter that asks for it. Before it replays anything, the
 * caller is checked on the aggregate's latest state, as a read of it would be: a caller who may not see it never
 * makes the log be read.
 *
 * The ref's tenant and the scope read in are what [targets] decide, as for a query: the caller's, or
 * the ones `Wow-Scope` names for a caller that may act across scopes (403 `wow.auth.forbidden` for another), unless
 * the aggregate type's tenant is fixed: then it is that tenant, and a `Wow-Scope` that names another
 * is 400 `wow.query.malformed`, as on the query routes. Whatever the caller may not see is 404 `wow.aggregate.not-found`, as a missing state is:
 * a state outside the caller's scope (an owner or a space it names and the state does not have; the
 * caller's owner is not checked for an aggregate of its tenant, as [ownerships] say), and one the
 * snapshot queries of its type hide from the caller (ABAC and the application's query policies); in a
 * trace, every commit's state is checked so, as each `?version=` read would be. Only the audit learns why. Every state,
 * and every event of a trace, is masked as the snapshot queries mask a state, for every caller alike:
 * both through the [ReadFacade] of `:query`, the one read path of every entry, which observes and
 * audits each read as a query of type `STATE` or `TRACE`. A deleted aggregate's state is read as
 * any other, `deleted` set. A query parameter that cannot be read, or is out of range, is 400 `wow.query.invalid`
 * with a violation naming it.
 */
internal class StateHandler(
    private val reads: StateReads,
    private val authenticator: Authenticator,
    private val headers: RequestHeaders,
    private val statuses: ErrorStatuses,
    private val targets: QueryTargets = QueryTargets.DEFAULT,
    private val ownerships: (AggregateType) -> Ownership = { Ownership.OWNER },
    queries: HttpQueries = HttpQueries.NONE,
    masks: PayloadMasks = PayloadMasks.NONE,
) {
    /** The one read path of the state reads: the query engine's, else over [queries] and [masks]. */
    private val facade: ReadFacade = queries.reads ?: ReadFacade(queries::visibility, masks)

    suspend fun handle(request: ServerRequest, type: AggregateType): ServerResponse {
        val reader = reader(request, type)
        val what = when {
            request.queryParam(WowPaths.VERSION).isPresent -> StateRead.VERSION
            request.queryParam(WowPaths.AT).isPresent -> StateRead.AT
            else -> StateRead.LATEST
        }
        val (view, state) = answering(type) {
            facade.observed(type, what, reader.caller) {
                val asked = asked(request, type)
                replaying(asked.parameter) {
                    if (what != StateRead.LATEST) {
                        // The caller is checked on the latest state before the log is replayed.
                        reader.visible { reads.latest(reader.ref) }
                    }
                    asked.minVersion?.let { reader.reached(it) } ?: reader.visible { asked.read(reads, reader.ref) }
                }
            }
        }
        val body = HttpJson.write(view.copy(state = state))
        val tag = EntityTags.state(view.version, body)
        val notModified = request.headers().header(HttpHeaders.IF_NONE_MATCH)
            .mapNotNull(EntityTags::parse).any { EntityTags.matchesWeakly(it, tag) }
        if (notModified) {
            return ServerResponse.status(NOT_MODIFIED).eTag(tag).buildAndAwait()
        }
        return ServerResponse.ok().eTag(tag).contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(body)
    }

    suspend fun trace(request: ServerRequest, type: AggregateType): ServerResponse {
        val reader = reader(request, type)
        val ref = reader.ref
        val masked = answering(type) {
            facade.observed(type, StateRead.TRACE, reader.caller, rows = { it.size.toLong() }) {
                val from = request.number(WowPaths.FROM, min = 1) ?: 1
                val to = request.number(WowPaths.TO, min = from) ?: Int.MAX_VALUE
                val limit = request.number(WowPaths.LIMIT, min = 1, max = MAX_TRACE_LIMIT) ?: DEFAULT_TRACE_LIMIT
                val commits = replaying(WowPaths.FROM) {
                    // The caller is checked on the latest state before the log is replayed.
                    reader.visible { reads.latest(ref) }
                    reads.trace(ref, from, to, limit)
                }
                if (commits.isEmpty()) {
                    throw StateNotFoundException(ref, Reason.MISSING, "No commit of $ref from version $from.")
                }
                commits.forEach { reader.checkScope(it.scope) }
                commits.map { commit ->
                    val state = facade.admitted(reader.caller, commit.committed(ref))
                        ?: throw StateNotFoundException(ref, Reason.HIDDEN, "No commit of $ref from version $from.")
                    commit.copy(
                        state = state,
                        events = commit.events.map { it.copy(body = facade.event(type, it.name, it.body)) }
                    )
                }
            }
        }
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(HttpJson.write(mapOf("ref" to ref, "commits" to masked)))
    }

    /** Authenticates and negotiates [request]: the scope the caller reads in, the ref it reads, and its query caller. */
    private suspend fun reader(request: ServerRequest, type: AggregateType): Reader {
        val caller = authenticator.authenticate(request)
        Negotiation.checkAcceptable(request, alsoEventStream = false)
        val target = headers.target(request, FrameworkErrors.QUERY_MALFORMED)
        val queryCaller = answering(type) { targets.of(type, caller, target) }
        val acting = checkNotNull(queryCaller.caller)
        val scope = ownerships(type).scopeOf(acting.scope)
        val tenant = acting.tenantId
        return Reader(scope, type.ref(tenant, request.pathVariable(CommandHandler.ID)), queryCaller)
    }

    /** Who reads which state: the [scope] it reads in, the [ref] it reads and the [caller] its queries are admitted as. */
    private inner class Reader(val scope: Scope, val ref: AggregateRef, val caller: QueryCaller) {
        /**
         * The state [load] reads, as [caller] sees it: [StateNotFoundException] when there is none, when it is
         * outside the caller's [scope] or when the query policies of its type hide it, each answered alike.
         */
        suspend fun visible(load: suspend () -> StateReadView?): Pair<StateReadView, JsonNode> =
            when (val read = facade.read(caller, load, { it.committed() }) { checkScope(it.scope) }) {
                is ReadFacade.Read.Visible -> read.view to read.state
                ReadFacade.Read.Missing -> throw StateNotFoundException(ref, Reason.MISSING)
                ReadFacade.Read.Hidden -> throw StateNotFoundException(ref, Reason.HIDDEN)
            }

        /**
         * The latest state at [minVersion] or later, as [caller] sees it; only a state it may see answers that it is
         * below [minVersion]: a hidden one, and one with no commit, are not found.
         */
        suspend fun reached(minVersion: Int): Pair<StateReadView, JsonNode> {
            var notReached: MinVersionNotReachedException? = null
            val read = visible {
                try {
                    reads.latest(ref, minVersion)
                } catch (behind: MinVersionNotReachedException) {
                    notReached = behind
                    if (behind.version == 0) null else reads.latest(ref)
                }
            }
            notReached?.let { behind ->
                throw ProblemException(
                    statuses.problemOf(
                        FrameworkErrors.QUERY_MIN_VERSION_NOT_REACHED,
                        behind.message!!,
                        ref.type.context
                    )
                        .copyWith(extensions = mapOf(WowPaths.VERSION to behind.version)),
                )
            }
            return read
        }

        fun checkScope(state: Scope) {
            if (!scope.covers(state)) {
                throw StateNotFoundException(ref, Reason.OUT_OF_SCOPE)
            }
        }
    }

    /**
     * What a state read asks for: the [version] or the instant [at] it reads, else the latest state, at [minVersion]
     * or later when it names one.
     */
    private class Asked(val version: Int?, val at: Instant?, val minVersion: Int? = null) {
        /** The query parameter that asks for a replay, or `""` for the latest state. */
        val parameter: String
            get() = when {
                version != null -> WowPaths.VERSION
                at != null -> WowPaths.AT
                else -> ""
            }

        suspend fun read(reads: StateReads, ref: AggregateRef): StateReadView? = when {
            version != null -> reads.at(ref, version)
            at != null -> reads.at(ref, at)
            else -> reads.latest(ref)
        }
    }

    /** What [request] asks for: the latest state, the one at `?version=` or the one as of `?at=`. */
    private fun asked(request: ServerRequest, type: AggregateType): Asked {
        val version = request.number(WowPaths.VERSION, min = 1)
        val at = request.instant(WowPaths.AT)
        val minVersion = request.number(WowPaths.MIN_VERSION, min = 1)
        if (version != null && at != null) {
            throw InvalidStateReadException(
                "A state of $type is read at a version or at a time, not both.",
                QueryErrorCodes.INVALID_REQUEST,
                WowPaths.AT,
            )
        }
        if (minVersion != null && (version != null || at != null)) {
            throw InvalidStateReadException(
                "A minimum version reads the latest state of $type, not one at a version or a time.",
                QueryErrorCodes.INVALID_REQUEST,
                WowPaths.MIN_VERSION,
            )
        }
        return Asked(version, at, minVersion)
    }

    /**
     * [read]'s result; a read whose replay is longer than the state reads replay is the client's request asking too
     * much on [parameter].
     */
    private suspend inline fun <T> replaying(parameter: String, read: () -> T): T = try {
        read()
    } catch (tooLong: StateReplayLimitException) {
        throw InvalidStateReadException(
            "The state asked for is more than ${tooLong.limit} commits from the aggregate's snapshot, or from its " +
                "first version: a state read replays at most ${tooLong.limit}.",
            QueryErrorCodes.REPLAY_TOO_LONG,
            parameter,
        )
    }

    /**
     * What [read] answers; a state the caller may not see is 404 with nothing of why, and a query error (a policy that
     * refuses the caller, a parameter the request got wrong, a bulkhead that is full) is that query error's problem,
     * with its violations, as on the query routes.
     */
    // QueryError is an interface of the query exceptions, which a catch cannot name: anything else propagates.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private suspend inline fun <T> answering(type: AggregateType, read: () -> T): T = try {
        read()
    } catch (notFound: StateNotFoundException) {
        throw problem(notFound.errorCode, notFound.message!!, type)
    } catch (refused: Exception) {
        if (refused !is QueryError) throw refused
        val problem = statuses.problemOf(refused.errorCode, refused.errorMsg, type.context).copyWith(
            violations = refused.bindingErrors.map { Violation(path = it.name, code = it.code, message = it.msg) },
        )
        throw ProblemException(problem)
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

        private fun StateReadView.committed() = CommittedState(ref, version, scope, deleted, tags, state)

        private fun CommitView.committed(ref: AggregateRef) = CommittedState(ref, version, scope, deleted, tags, state)

        /** The ISO-8601 instant of query parameter [name]; null when the request has none. */
        private fun ServerRequest.instant(name: String): Instant? {
            val value = queryParam(name).orElse(null) ?: return null
            return try {
                Instant.parse(value)
            } catch (_: DateTimeParseException) {
                throw InvalidStateReadException(
                    "'$name' is an ISO-8601 instant: '$value'.",
                    QueryErrorCodes.INVALID_VALUE,
                    name,
                )
            }
        }

        /** The whole number of query parameter [name], from [min] up to [max]; null when the request has none. */
        private fun ServerRequest.number(name: String, min: Int, max: Int = Int.MAX_VALUE): Int? {
            val value = queryParam(name).orElse(null) ?: return null
            val range = if (max == Int.MAX_VALUE) "from $min" else "from $min to $max"
            return value.toIntOrNull()?.takeIf { it in min..max } ?: throw InvalidStateReadException(
                "'$name' is a whole number $range: '$value'.",
                QueryErrorCodes.INVALID_VALUE,
                name,
            )
        }
    }
}
