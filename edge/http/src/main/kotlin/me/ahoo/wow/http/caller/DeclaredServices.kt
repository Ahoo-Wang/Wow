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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.wire.WowHeaders
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * The service a request to the internal port comes from, as it names itself in [WowHeaders.SERVICE]:
 * no token and no principal, the internal port being reachable by the platform's own pods alone. A request that names
 * a service is that service's [Caller]: its subject `service:<name>`, in the [tenantId], without scope, a
 * [service][Caller.service] identity, so the call routes take its `Wow-Call` and [CallPermits] what it may call or
 * read. A name is letters, digits, `.`, `_` and `-`, at most [MAX_NAME_LENGTH] characters; any other value names no
 * service.
 */
@InternalWowApi
object DeclaredServices {
    /** The prefix of a service identity's subject, and of the operator it would record on a command of its own. */
    const val SUBJECT_PREFIX: String = "service:"

    /** The most characters of a service name, a DNS name's. */
    const val MAX_NAME_LENGTH: Int = 253

    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /** Whether [name] is a service name a request may state. */
    fun isName(name: String): Boolean = name.length <= MAX_NAME_LENGTH && NAME.matches(name)

    /** The service [request] names, or null when it names none. */
    fun nameOf(request: ServerRequest): String? =
        request.headers().firstHeader(WowHeaders.SERVICE)?.trim()?.takeIf(::isName)

    /** The service identity of the service named [name], in [tenantId]. */
    fun callerOf(name: String, tenantId: String = Caller.DEFAULT_TENANT): Caller {
        require(isName(name)) { "[$name] is no service name." }
        return Caller(tenantId = tenantId, subject = SUBJECT_PREFIX + name, service = true)
    }

    /** The service each request names, as its [Caller] in [tenantId]; null for a request that names none. */
    fun resolver(tenantId: String = Caller.DEFAULT_TENANT): CallerResolver =
        CallerResolver { request -> nameOf(request)?.let { callerOf(it, tenantId) } }

    /** The service [caller] is, by name: its subject without [SUBJECT_PREFIX]. */
    fun serviceOf(caller: Caller): String? = caller.subject?.removePrefix(SUBJECT_PREFIX)
}
