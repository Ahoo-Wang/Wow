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

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.securityContextOrNull
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.runtime.admission.Authorization

/**
 * What the CoSec authorizers know of a [Caller]: whether it comes from a request CoSec's pipeline permitted, and as
 * whom. The pipeline writes the security context it permitted into the request's Reactor context, which the request's
 * coroutines carry (`ReactorContext`) through the edge's handling, admission included.
 *
 * - **Request-less**: no security context. A saga's, a timer's, a compensation's or a scheduled job's command, which
 *   carries the scope and operator of the root command admission authorized, or a call on the internal port, which
 *   CoSec's pipeline does not run on. So is a service identity ([Caller.service]), which only the internal port takes
 *   and its caller list decides. The CoSec authorizers have no objection to those.
 * - **CoSec's**: the context's principal is one a credential proved and is the caller, as [CoSecCallerResolver] builds
 *   it: the same subject in the same tenant. CoSec evaluates it.
 * - **Foreign**: any other caller of a request CoSec permitted, which CoSec cannot evaluate: one an application's
 *   `CallerResolver` mapped to another subject or tenant, an anonymous one, or one an application's code sends a
 *   command as while it handles the request. The CoSec authorizers deny it: abstaining would leave
 *   the per-action authorization, and the operational commands it decides, open without a word.
 */
internal sealed interface CoSecRequest {
    data object RequestLess : CoSecRequest

    data object Foreign : CoSecRequest

    @JvmInline
    value class Of(val security: SecurityContext) : CoSecRequest

    companion object {
        suspend fun of(caller: Caller, defaultTenant: String): CoSecRequest {
            if (caller.service) {
                return RequestLess
            }
            val context = currentCoroutineContext()[ReactorContext]?.context?.securityContextOrNull()
                ?: return RequestLess
            val principal = context.principal
            val ours = principal.authenticated && principal.id == caller.subject &&
                CoSecCallerResolver.tenantOf(context, defaultTenant) == caller.tenantId
            return if (ours) Of(context) else Foreign
        }

        /** Why a foreign caller is denied [name] on [resource]. */
        fun foreign(name: String, resource: String): Authorization.Denied = Authorization.Denied(
            "CoSec cannot authorize $name on $resource: the caller is not the principal CoSec authenticated for " +
                "the request. Send it as the request's caller, or decide such callers with an authorizer of your " +
                "own in place of wow.cosec.authorizer.enabled.",
        )
    }
}
