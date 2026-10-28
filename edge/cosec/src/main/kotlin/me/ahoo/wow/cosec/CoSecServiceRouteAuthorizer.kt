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

import kotlinx.coroutines.reactive.awaitFirstOrNull
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.RouteAction
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

/**
 * Authorizes a call of a service route (decision D359) with CoSec's policies, as [CoSecCommandAuthorizer] authorizes a
 * command: the call is evaluated as the request of the route's own method and the path it was called at, so the
 * policy that guards the route over HTTP guards it here too. The attributes [ROUTE] (`context.name`),
 * [CoSecCommandAuthorizer.TENANT], [CoSecCommandAuthorizer.OWNER] and [CoSecCommandAuthorizer.SPACE] are there for
 * condition matchers; the principal is rebuilt from the [Caller] as for a command.
 */
class CoSecServiceRouteAuthorizer(
    private val authorization: CoSecAuthorization,
    private val defaultTenant: String = Caller.DEFAULT_TENANT,
) : ServiceRouteAuthorizer {
    override suspend fun authorize(caller: Caller, action: RouteAction): Authorization {
        val attributes = buildMap {
            put(ROUTE, "${action.context}.${action.name}")
            put(CoSecCommandAuthorizer.TENANT, action.tenantId)
            action.scope.ownerId?.let { put(CoSecCommandAuthorizer.OWNER, it) }
            action.scope.spaceId?.let { put(CoSecCommandAuthorizer.SPACE, it) }
        }
        val request = ActionRequest(action.method, action.path, attributes)
        val result = authorization.authorize(request, coSecContextOf(caller, defaultTenant)).awaitFirstOrNull()
        val route = "${action.context}.${action.name}"
        return when {
            result == null -> Authorization.Denied("CoSec gave no decision on $route.")
            result.authorized -> Authorization.Allowed
            else -> Authorization.Denied(result.reason.ifBlank { "CoSec denies $route." })
        }
    }

    companion object {
        const val ROUTE: String = "wow.route"
    }
}
