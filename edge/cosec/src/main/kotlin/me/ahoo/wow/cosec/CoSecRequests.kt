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
import me.ahoo.cosec.webflux.ReactiveSecurityContexts.getSecurityContextOrEmpty
import me.ahoo.wow.api.caller.Caller

/**
 * The security context of the request a [Caller] comes from, for the CoSec authorizers: CoSec's
 * pipeline writes the context it permitted into the request's Reactor context, which the request's coroutines carry
 * (`ReactorContext`) through the edge's handling, admission included. A command or a route call is the request's
 * when that context is CoSec's (its `scope`) and its principal is the caller: the same subject in the
 * same tenant, and no service identity.
 *
 * A caller without one is request-less: a saga's, a timer's, a compensation's or a scheduled job's command, which
 * carries the scope and operator of the root command admission authorized, or a service's call on
 * the internal port, which its own authorizer decides. The CoSec authorizers have no objection to those.
 */
internal object CoSecRequests {
    suspend fun securityOf(caller: Caller, defaultTenant: String): SecurityContext? {
        if (caller.service) {
            return null
        }
        val subject = caller.subject ?: return null
        val context = currentCoroutineContext()[ReactorContext]?.context?.getSecurityContextOrEmpty() ?: return null
        if (context.scope == null || context.principal.id != subject) {
            return null
        }
        return context.takeIf { CoSecCallerResolver.tenantOf(it, defaultTenant) == caller.tenantId }
    }
}
