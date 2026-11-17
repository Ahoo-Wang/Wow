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

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * What [CoSecCallerResolver] takes from the host (`wow.tenant.default` and `wow.cosec.*`).
 *
 * - [defaultTenant] is the tenant of a principal in CoSec's default tenant, `(0)`.
 * - A principal acts across scopes ([Caller.crossScope]) when it has one of [crossScopeRoles] in the scope CoSec
 *   resolved for its request.
 * - A principal is a tenant-level caller when it has one of [tenantRoles] there: its caller has no owner, so it reads
 *   and writes every aggregate of its tenant, each action still authorized by CoSec. Any other principal is an
 *   owner-level caller, its own owner.
 *
 * Roles alone: CoSec 8 tells an application a principal's roles in the request's scope, never the policies it
 * attaches.
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
@ExperimentalWowApi
class CoSecCallerOptions @InternalWowApi constructor(
    val defaultTenant: String = Caller.DEFAULT_TENANT,
    val crossScopeRoles: Set<String> = emptySet(),
    val tenantRoles: Set<String> = emptySet(),
) {
    init {
        require(defaultTenant.isNotBlank()) { "The default tenant is not blank." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is CoSecCallerOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CoSecCallerOptions(defaultTenant=$defaultTenant, crossScopeRoles=$crossScopeRoles, tenantRoles=$tenantRoles)"

    private fun valueFields(): List<Any?> = listOf(defaultTenant, crossScopeRoles, tenantRoles)
}

/**
 * The caller of a request from the security context CoSec's pipeline built for it: CoSec
 * authenticates the request and resolves its scope, deciding it itself or, behind a CoSec gateway
 * (`cosec.pipeline.mode=gateway`), reading what the gateway verified from `CoSec-Identity`. This resolver turns that
 * context into a [Caller].
 *
 * - **Unauthenticated**: no security context, or one whose principal no credential proved (CoSec's
 *   `authenticated`), is no caller: the edge answers 401.
 * - **Tenant**: the security context's; CoSec's default tenant `(0)` is [CoSecCallerOptions.defaultTenant].
 * - **Owner and subject**: the principal's id, the subject always. The owner is the principal's id for an owner-level
 *   caller, and none for a tenant-level one ([CoSecCallerOptions.tenantRoles]),
 *   such as an employee who ships every buyer's orders: the parts absent from a caller's scope are not checked.
 * - **Space**: the space of CoSec's scope, which CoSec validated (`CoSec-Space-Id`, a space the principal has a role
 *   in); its default space is none. `Wow-Space` is not read: on a CoSec host the client names its space to CoSec.
 * - **Application** ([Caller.app]): the app of CoSec's scope, the one CoSec evaluated the request in: the host's
 *   (`cosec.authorization.scope.app-id`), else the one the request's credential declares (a token's `aud`, an API
 *   key's `app`), or, behind a gateway, the one the gateway forwarded. None when the scope names no app.
 * - **Cross scope**: [CoSecCallerOptions.crossScopeRoles].
 * - **Never a service identity**: a service names itself on the internal port (`Wow-Service`), never
 *   through CoSec, so no principal asserts a synchronous call's root command, whatever headers its request carries.
 * - **Attributes**: the principal's roles in its scope ([ROLES]), and every other attribute of the principal under its
 *   own name, the claims an access policy reads (`caller.claims("departments")`); a value is a list, an array or a
 *   comma-separated string. An attribute named as one of those the caller holds already ([ROLES], [Caller.APP]) is
 *   not a claim: CoSec's roles and application win.
 */
@ExperimentalWowApi
class CoSecCallerResolver(private val options: CoSecCallerOptions = CoSecCallerOptions()) : CallerResolver {
    override suspend fun resolve(request: ServerRequest): Caller? =
        request.exchange().getSecurityContext()?.let(::callerOf)

    /** The caller of [context]; null when it is anonymous, or not one CoSec's pipeline built. */
    fun callerOf(context: SecurityContext): Caller? {
        val principal = context.principal
        if (!principal.authenticated) {
            return null
        }
        val scope = context.scope
        val tenantLevel = scope.roles.any { it in options.tenantRoles }
        return Caller(
            tenantId = tenantOf(context, options.defaultTenant),
            scope = Scope(
                ownerId = principal.id.takeUnless { tenantLevel },
                spaceId = scope.spaceId.trim().ifEmpty { null },
            ),
            subject = principal.id,
            crossScope = scope.roles.any { it in options.crossScopeRoles },
            attributes = attributesOf(scope.roles, principal) + scope.appId.trim().ifEmpty { null }?.let {
                mapOf(Caller.APP to listOf(it))
            }.orEmpty(),
        )
    }

    private fun attributesOf(roles: List<String>, principal: CoSecPrincipal): Map<String, List<String>> {
        val attributes = LinkedHashMap<String, List<String>>()
        roles.takeIf { it.isNotEmpty() }?.let { attributes[ROLES] = it.distinct().sorted() }
        principal.attributes.forEach { (key, value) ->
            val values = valuesOf(value)
            if (key.isNotBlank() && key !in RESERVED && values.isNotEmpty()) {
                attributes[key] = values
            }
        }
        return attributes
    }

    companion object {
        /** The attribute of a [Caller] listing its principal's CoSec roles in the scope of its request. */
        const val ROLES: String = "roles"

        /** The attributes a caller holds from CoSec itself, which no attribute of the principal takes. */
        private val RESERVED = setOf(ROLES, Caller.APP)

        /** The Wow tenant of [context]: its CoSec tenant, CoSec's default one being [defaultTenant]. */
        internal fun tenantOf(context: SecurityContext, defaultTenant: String): String =
            context.tenantId.takeUnless { it == SecurityContext.DEFAULT_TENANT_ID } ?: defaultTenant

        internal fun valuesOf(value: Any?): List<String> = when (value) {
            null -> emptyList()
            is Iterable<*> -> value.mapNotNull { it?.toString()?.trim() }
            is Array<*> -> value.mapNotNull { it?.toString()?.trim() }
            else -> value.toString().split(',').map(String::trim)
        }.filter(String::isNotEmpty).distinct()
    }
}
