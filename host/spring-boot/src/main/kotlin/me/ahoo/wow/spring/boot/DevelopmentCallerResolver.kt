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

package me.ahoo.wow.spring.boot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * The host's callers in development ([DevelopmentMode]), when the application has no `CallerResolver` and CoSec is off:
 * the caller the request's identity headers name, so a service runs and is tried out at every caller level without an
 * identity provider. Any client sets these headers, so the host wires it in development alone; a node outside it
 * without a resolver refuses to start, and the gateway in front of a deployment overwrites the `Wow-*` identity
 * headers.
 *
 * - [USER]: the caller's subject. Without it, the request is the anonymous caller of [defaultTenant], and the other
 *   identity headers are ignored but [APP].
 * - [OWNER]: the owner of its scope, an owner-level caller that reads and writes its own aggregates alone; without it,
 *   a tenant-level caller. The owner is never the user unless the header says so.
 * - [ROLES]: its roles, comma-separated, the attribute [ROLES_ATTRIBUTE] as CoSec's resolver names it.
 * - [TENANT]: its tenant; [defaultTenant] (`wow.tenant.default`) without it.
 * - [APP]: the application it calls in, its standard attribute [Caller.APP], which the edge records as each command's
 *   request fact `app_id`; CoSec's resolver takes the one CoSec authorized instead. The anonymous caller keeps it too.
 */
class DevelopmentCallerResolver(private val defaultTenant: String = Caller.DEFAULT_TENANT) : CallerResolver {
    private val anonymous = if (defaultTenant == Caller.DEFAULT_TENANT) Caller.ANONYMOUS else Caller(defaultTenant)

    override suspend fun resolve(request: ServerRequest): Caller {
        val headers = request.headers()
        val app = headers.firstHeader(APP).value()?.let { mapOf(Caller.APP to listOf(it)) }.orEmpty()
        val user = headers.firstHeader(USER).value()
        if (user == null) {
            val ignored = IDENTITY.filter { headers.firstHeader(it) != null }
            if (ignored.isNotEmpty()) {
                logger.debug { "No $USER header: the anonymous caller, ignoring ${ignored.joinToString()}." }
            }
            return if (app.isEmpty()) anonymous else Caller.of(tenantId = defaultTenant, attributes = app)
        }
        val roles = headers.firstHeader(ROLES)?.split(',')?.mapNotNull { it.value() }.orEmpty()
        return Caller.of(
            tenantId = headers.firstHeader(TENANT).value() ?: defaultTenant,
            scope = headers.firstHeader(OWNER).value()?.let { Scope(ownerId = it) } ?: Scope.NONE,
            subject = user,
            attributes = (if (roles.isEmpty()) emptyMap() else mapOf(ROLES_ATTRIBUTE to roles)) + app,
        )
    }

    private fun String?.value(): String? = this?.trim()?.ifEmpty { null }

    companion object {
        /** The header naming the caller's subject. */
        const val USER: String = "Wow-User"

        /** The header naming the owner of the caller's scope. */
        const val OWNER: String = "Wow-Owner"

        /** The header listing the caller's roles, comma-separated. */
        const val ROLES: String = "Wow-Roles"

        /** The header naming the caller's tenant. */
        const val TENANT: String = "Wow-Tenant"

        /** The header naming the application the caller calls in. */
        const val APP: String = "Wow-App"

        /** The caller attribute listing its roles, as CoSec's resolver names it. */
        const val ROLES_ATTRIBUTE: String = "roles"

        /** The identity headers that only name a caller with [USER]. */
        private val IDENTITY = listOf(OWNER, ROLES, TENANT)

        private val logger = KotlinLogging.logger {}
    }
}
