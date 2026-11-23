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

package me.ahoo.wow.http.caller

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.ProblemException
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * The caller of a route: [resolver]'s, or a 401 problem when it accepts no credentials; a 403 problem when the request
 * named a space (`Wow-Space`) the caller does not act in.
 *
 * A [service][Caller.service] identity is taken on the [internal port][internalPort] alone, where a service names
 * itself and only the platform's own services reach: on any other route (the public routes, the operator's routes, the
 * subscription routes) a resolver's service identity is refused with 403 `wow.auth.forbidden`, so no request to them
 * is trusted as a service, whatever its resolver says. A service's own code in the process is no request and passes
 * through none of these.
 */
internal class Authenticator(
    private val resolver: CallerResolver,
    private val statuses: ErrorStatuses,
    /** Whether these are the internal port's routes, which alone take a service identity. */
    private val internalPort: Boolean = false,
) {
    suspend fun authenticate(request: ServerRequest): Caller {
        val caller = resolver.resolve(request) ?: throw ProblemException(
            statuses.problemOf(FrameworkCodes.AUTH_UNAUTHENTICATED, "The request carries no credentials accepted here."),
        )
        forbidden(request, caller)?.let { detail ->
            throw ProblemException(statuses.problemOf(FrameworkCodes.AUTH_FORBIDDEN, detail))
        }
        return caller
    }

    /** Why [caller] may not make [request] here, or null when it may. */
    private fun forbidden(request: ServerRequest, caller: Caller): String? {
        if (caller.service && !internalPort) {
            return serviceRefused(caller)
        }
        val space = request.headers().firstHeader(WowHeaders.SPACE)
        return "The caller does not act in space '${space?.trim()}'."
            .takeIf { space != null && space.trim() != caller.scope.spaceId }
    }
}

/** Why [caller], a service identity a resolver gave a request outside the internal port, is refused. */
internal fun serviceRefused(caller: Caller): String =
    "The caller resolved for this request is a service identity (${caller.subject}), which is taken on the " +
        "internal port alone: a client of this port is an ordinary caller, with its roles " +
        "(Caller.of(subject = ..., roles = ...))."
