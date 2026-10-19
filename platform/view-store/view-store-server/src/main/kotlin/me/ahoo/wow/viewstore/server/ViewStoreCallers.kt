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

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.command.ScopeHeader
import me.ahoo.wow.runtime.admission.TargetScope
import me.ahoo.wow.viewstore.api.ViewStoreContext
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * Who calls the view store: the 9.x CoSec gateway rules, as the callers of decision D189. It reads request headers
 * and stands in for CoSec (`wow-cosec`), whose resolver gives the same callers from a token, so the service runs
 * without an identity provider; a deployment puts CoSec's resolver in its place (the `cosec` profile), behind the
 * same reserved owners ([ReservedScopeCallers]).
 *
 * - `X-User: <id>`: the user, an owner-level caller whose owner is the user, in the tenant `X-Tenant` (`(0)` without
 *   it); `X-Role` lists its roles, comma-separated ([SHARED_WRITER], [SYSTEM_WRITER]).
 * - `CoSec-App-Id`: the calling application, the caller's standard attribute [Caller.APP] (decision D232), which the
 *   edge records as each command's request fact `app_id` and the query policy keeps the queries in. Like the user,
 *   it is taken as the request sends it here; CoSec's resolver takes only the application CoSec authorized.
 * - `Wow-Scope` naming the owner `(shared)`, or the tenant `(platform)` with the owner `(system)`: the caller acts as
 *   that reserved owner, which 9.x addressed by the path `…/owner/(shared)/…`. Every caller may read there; the
 *   [ViewStoreAuthorizer] decides who writes. Any other `Wow-Scope` is refused by admission (`wow.auth.forbidden`):
 *   the caller may not act as another user.
 * - Neither `X-User`: the anonymous caller of the default tenant, which reads only the reserved owners' views.
 */
class ViewStoreCallers : CallerResolver {
    override suspend fun resolve(request: ServerRequest): Caller {
        val headers = request.headers()
        val user = headers.firstHeader(USER)?.trim()?.ifEmpty { null }
        val tenant = headers.firstHeader(TENANT)?.trim()?.ifEmpty { null } ?: Caller.DEFAULT_TENANT
        val roles = headers.firstHeader(ROLES)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val app = headers.firstHeader(ViewStoreContext.APP_ID_HEADER)?.trim()?.ifEmpty { null }
        val attributes = listOfNotNull(
            ROLES_ATTRIBUTE to roles,
            app?.let { Caller.APP to listOf(it) },
        ).toMap().filterValues { it.isNotEmpty() }
        val reserved = reservedTarget(headers.firstHeader(WowHeaders.SCOPE))
        return Caller.of(
            tenantId = tenant,
            scope = reserved?.scope ?: user?.let { Scope(ownerId = it) } ?: Scope.NONE,
            subject = user,
            crossScope = reserved != null,
            attributes = attributes,
        )
    }

    companion object {
        /**
         * The reserved scope [value] names, when it names only one: the owner `(shared)` alone, or the tenant
         * `(platform)` with the owner `(system)`. Null for no header, one that does not parse (admission answers it)
         * and any other.
         */
        internal fun reservedTarget(value: String?): TargetScope? {
            val target = value?.let { runCatching { ScopeHeader.parse(it) }.getOrNull() } ?: return null
            return target.takeIf { it in RESERVED }
        }

        /** The header naming the user. */
        const val USER: String = "X-User"

        /** The header naming the user's tenant. */
        const val TENANT: String = "X-Tenant"

        /** The header listing the user's roles. */
        const val ROLES: String = "X-Role"

        /** The caller attribute listing its roles, as CoSec's resolver names it. */
        const val ROLES_ATTRIBUTE: String = "roles"

        /** The role that writes the owner `(shared)`'s views and preferences and the tenant's dataset wording. */
        const val SHARED_WRITER: String = "view-store.shared-writer"

        /** The role that writes the stored system views, under the tenant `(platform)`. */
        const val SYSTEM_WRITER: String = "view-store.system-writer"

        /** The `Wow-Scope` of the shared views and of the stored system views. */
        val SHARED: TargetScope = TargetScope(scope = Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))
        val SYSTEM: TargetScope =
            TargetScope(ViewStoreContext.SYSTEM_TENANT_ID, Scope(ownerId = ViewStoreContext.SYSTEM_OWNER_ID))
        private val RESERVED = setOf(SHARED, SYSTEM)
    }
}
