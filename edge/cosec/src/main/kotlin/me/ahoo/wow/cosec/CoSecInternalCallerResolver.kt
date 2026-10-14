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

package me.ahoo.wow.cosec

import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.InternalCallerResolver
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.server.ServerWebExchange

/**
 * The callers of the internal port's call routes from CoSec (decision D193): the internal port runs no CoSec web
 * filter, so this parses the request's security context itself, with CoSec's [requests] and [contexts] parsers (its
 * token, validated as CoSec's filter validates it), and gives the caller [callers] makes of it. A principal of
 * `wow.cosec.service.roles` / `wow.cosec.service.policies` is a service identity, the only one the call routes serve;
 * a request without a valid token, or with an expired one, is no caller (401).
 */
class CoSecInternalCallerResolver(
    private val requests: RequestParser<ServerWebExchange>,
    private val contexts: SecurityContextParser,
    private val callers: CoSecCallerResolver,
) : InternalCallerResolver {
    @Suppress("TooGenericExceptionCaught") // A token that does not parse or has expired authenticates no one.
    override suspend fun resolve(request: ServerRequest): Caller? {
        val context = try {
            contexts.parse(requests.parse(request.exchange()))
        } catch (_: Exception) {
            return null
        }
        return callers.callerOf(context)
    }
}
