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

package me.ahoo.wow.ops.server

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * Who calls the ops service: an operator, a tenant-level caller (decision D189), read from request headers. It stands
 * in for CoSec (`wow-cosec`), whose resolver gives the same callers from a token (its operators' roles listed in
 * `wow.cosec.tenant-level.roles`), so the service runs without an identity provider; a deployment puts CoSec's
 * resolver in its place, and CoSec's command authorizer behind [OpsAuthorizer].
 *
 * - `X-User: <id>`: the operator, without an owner, so it reads every entry of its tenant, `X-Tenant` (`(0)` without
 *   it, the tenant the services' ledgers publish to); `X-Role` lists its roles, comma-separated: [OPERATOR] acts on
 *   the entries ([OperatorRole]).
 * - No `X-User`: no caller, so the request is answered 401: the ledger is not the anonymous caller's to read.
 */
class OpsCallers : CallerResolver {
    override suspend fun resolve(request: ServerRequest): Caller? {
        val headers = request.headers()
        val user = headers.firstHeader(USER)?.trim()?.ifEmpty { null } ?: return null
        val tenant = headers.firstHeader(TENANT)?.trim()?.ifEmpty { null } ?: Caller.DEFAULT_TENANT
        val roles = headers.firstHeader(ROLES)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return Caller.of(
            tenantId = tenant,
            scope = Scope.NONE,
            subject = user,
            attributes = if (roles.isEmpty()) emptyMap() else mapOf(ROLES_ATTRIBUTE to roles),
        )
    }

    companion object {
        /** The header naming the operator. */
        const val USER: String = "X-User"

        /** The header naming the operator's tenant. */
        const val TENANT: String = "X-Tenant"

        /** The header listing the operator's roles. */
        const val ROLES: String = "X-Role"

        /** The caller attribute listing its roles, as CoSec's resolver names it. */
        const val ROLES_ATTRIBUTE: String = "roles"

        /** The role that acts on the entries: the ledger's system commands, `wow.ledger.*`. */
        const val OPERATOR: String = "ops.operator"
    }
}
