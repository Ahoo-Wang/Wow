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

package me.ahoo.wow.viewstore.server

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * The view store's reserved owners behind another resolver, CoSec's (roadmap P.15): a caller whose `Wow-Scope` names
 * the owner `(shared)`, or the tenant `(platform)` with the owner `(system)`, acts as that owner, as
 * [ViewStoreCallers] has it from headers and 9.x had it from the path `…/owner/(shared)/…`. Every authenticated caller
 * reads there; the [ViewStoreAuthorizer] decides who writes, by the roles [callers] gave it. Any other `Wow-Scope` is
 * left to [callers] and admission: a user does not act as another one.
 */
class ReservedScopeCallers(private val callers: CallerResolver) : CallerResolver {
    override suspend fun resolve(request: ServerRequest): Caller? {
        val caller = callers.resolve(request) ?: return null
        val reserved = ViewStoreCallers.reservedTarget(request.headers().firstHeader(WowHeaders.SCOPE))
            ?: return caller
        return Caller.of(
            tenantId = caller.tenantId,
            scope = reserved.scope,
            subject = caller.subject,
            crossScope = true,
            attributes = caller.attributes,
            service = caller.service,
        )
    }
}
