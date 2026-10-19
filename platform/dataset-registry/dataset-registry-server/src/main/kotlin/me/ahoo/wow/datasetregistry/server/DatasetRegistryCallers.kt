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

package me.ahoo.wow.datasetregistry.server

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.datasetregistry.api.DatasetRegistryContext
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * Who calls the dataset registry, read from request headers: a stand-in for CoSec, whose resolver gives the same
 * callers from a token, so the service runs without an identity provider; a deployment puts CoSec's resolver in its
 * place.
 *
 * - `X-User: <id>`: the caller, of the platform's tenant ([DatasetRegistryContext.TENANT_ID], every source's), unless
 *   `X-Tenant` names another; `X-Role` lists its roles, comma-separated ([ADMIN], [APPROVER], [VIEWER]).
 * - No `X-User`: no caller, so the request is answered 401.
 */
class DatasetRegistryCallers : CallerResolver {
    override suspend fun resolve(request: ServerRequest): Caller? {
        val headers = request.headers()
        val user = headers.firstHeader(USER)?.trim()?.ifEmpty { null } ?: return null
        val tenant = headers.firstHeader(TENANT)?.trim()?.ifEmpty { null } ?: DatasetRegistryContext.TENANT_ID
        val roles = headers.firstHeader(ROLES)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return Caller.of(
            tenantId = tenant,
            scope = Scope.NONE,
            subject = user,
            attributes = if (roles.isEmpty()) emptyMap() else mapOf(ROLES_ATTRIBUTE to roles),
        )
    }

    companion object {
        const val USER: String = "X-User"
        const val TENANT: String = "X-Tenant"
        const val ROLES: String = "X-Role"

        /** The caller attribute listing its roles, as CoSec's resolver names it. */
        const val ROLES_ATTRIBUTE: String = "roles"

        /** Drafts, revises, reviews and syncs the sources. */
        const val ADMIN: String = "dataset-registry.admin"

        /** Approves a lower protection of a Wow source's values (decision D241 ③): the privileged review. */
        const val APPROVER: String = "dataset-registry.approver"

        /** Reads the sources. */
        const val VIEWER: String = "dataset-registry.viewer"

        /** The roles that read the sources. */
        @JvmField
        val READERS: Set<String> = setOf(ADMIN, APPROVER, VIEWER)

        /** The roles of [caller]. */
        fun rolesOf(caller: Caller): List<String> = caller.attributes[ROLES_ATTRIBUTE].orEmpty()
    }
}
