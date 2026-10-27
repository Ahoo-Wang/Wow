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
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer

/**
 * The action-level authorizer of the commands a [service][Caller.service] identity sends, its synchronous calls on the
 * internal port above all: the internal port runs no web filter, so this is where
 * a service's call is authorized, whatever the host's [CommandAuthorizer] is. The host takes it when a module or the
 * application gives one (CoSec's, as the service principal, whether or not `wow.cosec.authorizer.enabled` is set);
 * every other caller stays the host's authorizer's ([over]).
 */
fun interface CallAuthorizer {
    suspend fun authorize(caller: Caller, action: CommandAction): Authorization

    /** An authorizer that authorizes the service identities' commands with this one, every other with [base]. */
    fun over(base: CommandAuthorizer): CommandAuthorizer = ServiceCallsAuthorizer(this, base)
}

private class ServiceCallsAuthorizer(
    private val calls: CallAuthorizer,
    private val base: CommandAuthorizer,
) : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization =
        if (caller.service) calls.authorize(caller, action) else base.authorize(caller, action)

    override fun toString(): String = "ServiceCallsAuthorizer(calls=$calls, base=$base)"
}
