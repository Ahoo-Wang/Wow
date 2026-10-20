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
import me.ahoo.wow.client.wire.WowHeaders
import me.ahoo.wow.http.problem.ErrorStatuses
import me.ahoo.wow.http.problem.ProblemException
import me.ahoo.wow.runtime.FrameworkCodes
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * The caller of every public route: [resolver]'s, or a 401 problem when it accepts no credentials; a 403 problem when
 * the request named a space (`Wow-Space`) the caller does not act in (decision D96).
 */
internal class Authenticator(private val resolver: CallerResolver, private val statuses: ErrorStatuses) {
    suspend fun authenticate(request: ServerRequest): Caller {
        val caller = resolver.resolve(request) ?: throw ProblemException(
            statuses.problemOf(FrameworkCodes.UNAUTHENTICATED, "The request carries no credentials accepted here."),
        )
        val space = request.headers().firstHeader(WowHeaders.SPACE)
        if (space != null && space.trim() != caller.scope.spaceId) {
            throw ProblemException(
                statuses.problemOf(
                    FrameworkCodes.AUTH_FORBIDDEN,
                    "The caller does not act in space '${space.trim()}'."
                ),
            )
        }
        return caller
    }
}
