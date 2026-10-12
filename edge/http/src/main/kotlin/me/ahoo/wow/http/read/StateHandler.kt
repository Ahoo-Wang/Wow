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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait

/**
 * `GET /{context}/{aggregate}/{id}/state` and `?version=` (decision D112): the [StateView] of [reads], with
 * `ETag: "<version>"`; 304 when `If-None-Match` names that tag.
 *
 * The ref's tenant is the caller's, or the one `Wow-Scope` names for a caller that may act across scopes (403
 * `wow.auth.forbidden` for another). A state outside the caller's scope (an owner or a space it names and the state
 * does not have) is 403 `wow.aggregate.forbidden`, as for a command (decision D94); a missing one is 404
 * `wow.aggregate.not-found`. A deleted aggregate's state is read as any other, `deleted` set.
 */
internal class StateHandler(
    private val reads: StateReads,
    private val authenticator: Authenticator,
    private val headers: RequestHeaders,
    private val statuses: ErrorStatuses,
) {
    suspend fun handle(request: ServerRequest, type: AggregateType): ServerResponse {
        val caller = authenticator.authenticate(request)
        Negotiation.checkAcceptable(request, alsoEventStream = false)
        val target = headers.target(request)
        if (target != null && !caller.crossScope) {
            throw problem(FrameworkCodes.AUTH_FORBIDDEN, "The caller may not read in another scope.", type)
        }
        val scope = target?.scope ?: caller.scope
        val ref = type.ref(target?.tenantId ?: caller.tenantId, request.pathVariable(CommandHandler.ID))
        val view = read(request, ref)
        if (!scope.covers(view.scope)) {
            throw problem(FrameworkCodes.AGGREGATE_FORBIDDEN, "$ref is outside the caller's scope.", type)
        }
        val tag = "\"${view.version}\""
        if (request.headers().header(HttpHeaders.IF_NONE_MATCH).any { matches(it, tag) }) {
            return ServerResponse.status(NOT_MODIFIED).eTag(tag).buildAndAwait()
        }
        return ServerResponse.ok().eTag(tag).contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(HttpJson.write(view))
    }

    /** The state of [ref] the request asks for: the latest, or the one at `?version=`. */
    private suspend fun read(request: ServerRequest, ref: AggregateRef): StateView {
        val version = request.queryParam(WowPaths.VERSION).orElse(null)?.let { value ->
            value.toIntOrNull()?.takeIf { it >= 1 }
                ?: throw problem(FrameworkCodes.MALFORMED, "A version is a whole number from 1: '$value'.", ref.type)
        }
        val view = if (version == null) reads.latest(ref) else reads.at(ref, version)
        val at = version?.let { " at version $it" }.orEmpty()
        return view ?: throw problem(FrameworkCodes.NOT_FOUND, "No state of $ref$at.", ref.type)
    }

    private fun problem(code: String, detail: String, type: AggregateType) =
        ProblemException(statuses.problemOf(code, detail, type.context))

    private companion object {
        const val NOT_MODIFIED = 304

        /** Whether this scope, the reader's, admits a state of [scope]: a field it names, the state has too. */
        fun Scope.covers(scope: Scope): Boolean =
            (ownerId == null || ownerId == scope.ownerId) && (spaceId == null || spaceId == scope.spaceId)

        fun matches(ifNoneMatch: String, tag: String): Boolean =
            ifNoneMatch.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == tag }
    }
}
