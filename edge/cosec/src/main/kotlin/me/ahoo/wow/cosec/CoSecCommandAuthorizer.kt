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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer

/**
 * Authorizes a command with CoSec's documents, as its caller (decision D367): the command is the action
 * `<context>:<command>` on the resource `<aggregate>/<id>` ([CoSecActions]), the name CoSec's route map gives its
 * HTTP route, so one policy guards both. CoSec's [checker] evaluates it for the principal and scope of the request
 * the caller comes from ([CoSecRequests]); a command it does not permit is denied.
 *
 * A request-less caller, such as a saga's, a timer's, a compensation's or a scheduled job's command, which carries
 * the scope and operator of the root command admission authorized (decision D298), gets no objection: CoSec has no
 * request of its to evaluate, and the node's other authorizers still apply. On the public port CoSec's pipeline has
 * already decided the command's route by the same action, so the host leaves this authorizer off unless
 * `wow.cosec.authorizer.enabled` asks for it.
 */
class CoSecCommandAuthorizer(
    private val checker: AuthorizationChecker,
    private val defaultTenant: String = Caller.DEFAULT_TENANT,
) : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization {
        val security = CoSecRequests.securityOf(caller, defaultTenant) ?: return Authorization.Allowed
        val name = CoSecActions.command(action)
        val resource = CoSecActions.resource(action)
        return if (checker.check(security, name, resource)) {
            Authorization.Allowed
        } else {
            Authorization.Denied("CoSec does not permit $name on $resource.")
        }
    }
}
