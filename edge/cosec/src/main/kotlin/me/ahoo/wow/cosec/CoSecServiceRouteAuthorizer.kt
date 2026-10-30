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

import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.RouteAction
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer

/**
 * Authorizes a call of a service route with CoSec's documents, as [CoSecCommandAuthorizer] authorizes a command: the
 * call is the action `<context>:<route>` on the resource `route/<route>` ([CoSecActions]), the name CoSec's route
 * map gives the route over HTTP. A request-less caller gets no objection.
 */
@ExperimentalWowApi
class CoSecServiceRouteAuthorizer(
    private val checker: AuthorizationChecker,
    private val defaultTenant: String = Caller.DEFAULT_TENANT,
) : ServiceRouteAuthorizer {
    override suspend fun authorize(caller: Caller, action: RouteAction): Authorization {
        val security = CoSecRequests.securityOf(caller, defaultTenant) ?: return Authorization.Allowed
        val name = CoSecActions.route(action)
        val resource = CoSecActions.routeResource(action.name)
        return if (checker.check(security, name, resource)) {
            Authorization.Allowed
        } else {
            Authorization.Denied("CoSec does not permit $name on $resource.")
        }
    }
}
