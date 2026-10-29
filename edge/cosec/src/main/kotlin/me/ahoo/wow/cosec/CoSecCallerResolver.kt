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
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * What [CoSecCallerResolver] takes from the host (`wow.tenant.default` and `wow.cosec.*`).
 *
 * - [defaultTenant] is the tenant of a principal in CoSec's default tenant, `(0)`.
 * - A principal acts across scopes ([Caller.crossScope]) when it has one of [crossScopeRoles] or one of
 *   [crossScopePolicies] in the scope CoSec resolved for its request.
 * - A principal is a tenant-level caller when it has one of [tenantRoles] or one of [tenantPolicies]: its caller has
 *   no owner, so it reads and writes every aggregate of its tenant, each action still authorized by CoSec. Any other
 *   principal is an owner-level caller, its own owner.
 * - The principal's attributes whose key starts with [attributePrefix] are its ABAC attributes.
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class CoSecCallerOptions @InternalWowApi constructor(
    val defaultTenant: String = Caller.DEFAULT_TENANT,
    val crossScopeRoles: Set<String> = emptySet(),
    val crossScopePolicies: Set<String> = emptySet(),
    val attributePrefix: String = ABAC_PREFIX,
    val tenantRoles: Set<String> = emptySet(),
    val tenantPolicies: Set<String> = emptySet(),
) {
    init {
        require(defaultTenant.isNotBlank()) { "The default tenant is not blank." }
        require(attributePrefix.isNotBlank()) { "The ABAC attribute prefix is not blank." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is CoSecCallerOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CoSecCallerOptions(defaultTenant=$defaultTenant, crossScopeRoles=$crossScopeRoles, " +
            "crossScopePolicies=$crossScopePolicies, attributePrefix=$attributePrefix, tenantRoles=$tenantRoles, " +
            "tenantPolicies=$tenantPolicies)"

    private fun valueFields(): List<Any?> = listOf(
        defaultTenant,
        crossScopeRoles,
        crossScopePolicies,
        attributePrefix,
        tenantRoles,
        tenantPolicies,
    )

    companion object {
        /** The prefix of the principal attributes that are ABAC attributes, as 9.x wow-cosec reads them. */
        const val ABAC_PREFIX: String = "abac."
    }
}

/**
 * The caller of a request from the security context CoSec's pipeline built for it: CoSec
 * authenticates the request and resolves its scope, deciding it itself or, behind a CoSec gateway
 * (`cosec.pipeline.mode=gateway`), reading what the gateway verified from `CoSec-Identity`. This resolver turns that
 * context into a [Caller].
 *
 * - **Unauthenticated**: no security context, one CoSec's pipeline did not build (it has no
 *   `scope`), or an anonymous principal, is no caller: the edge answers 401.
 * - **Tenant**: the security context's; CoSec's default tenant `(0)` is [CoSecCallerOptions.defaultTenant].
 * - **Owner and subject**: the principal's id, the subject always. The owner is the principal's id for an owner-level
 *   caller, and none for a tenant-level one ([CoSecCallerOptions.tenantRoles], [CoSecCallerOptions.tenantPolicies]),
 *   such as an employee who ships every buyer's orders: the parts absent from a caller's scope are not checked.
 * - **Space**: the space of CoSec's scope, which CoSec validated (`CoSec-Space-Id`, a space the principal has a role
 *   in); its default space is none. `Wow-Space` is not read: on a CoSec host the client names its space to CoSec.
 * - **Application** ([Caller.app]): the app of CoSec's scope, the one CoSec evaluated the request in: the host's
 *   (`cosec.authorization.scope.app-id`), or the one the request declared (`CoSec-App-Id`) and CoSec accepted, or,
 *   behind a gateway, the one the gateway forwarded. None when the scope names no app.
 * - **Cross scope**: [CoSecCallerOptions.crossScopeRoles] and [CoSecCallerOptions.crossScopePolicies].
 * - **Never a service identity**: a service names itself on the internal port (`Wow-Service`), never
 *   through CoSec, so no principal asserts a synchronous call's root command, whatever headers its request carries.
 * - **Attributes**: the principal's roles in its scope ([ROLES]), its policies ([POLICIES]), and its ABAC attributes,
 *   keyed with their prefix (`abac.dept`); a value is a list, an array or a comma-separated string.
 */
class CoSecCallerResolver(private val options: CoSecCallerOptions = CoSecCallerOptions()) : CallerResolver {
    override suspend fun resolve(request: ServerRequest): Caller? =
        request.exchange().getSecurityContext()?.let(::callerOf)

    /** The caller of [context]; null when it is anonymous, or not one CoSec's pipeline built. */
    fun callerOf(context: SecurityContext): Caller? {
        val scope = context.scope ?: return null
        val principal = context.principal
        if (principal.anonymous || !principal.authenticated) {
            return null
        }
        val tenantLevel = principal.roles.any { it in options.tenantRoles } ||
            principal.policies.any { it in options.tenantPolicies }
        return Caller(
            tenantId = tenantOf(context, options.defaultTenant),
            scope = Scope(
                ownerId = principal.id.takeUnless { tenantLevel },
                spaceId = scope.spaceId.trim().ifEmpty { null },
            ),
            subject = principal.id,
            crossScope = principal.roles.any { it in options.crossScopeRoles } ||
                principal.policies.any { it in options.crossScopePolicies },
            attributes = attributesOf(principal) + scope.appId.trim().ifEmpty { null }?.let {
                mapOf(Caller.APP to listOf(it))
            }.orEmpty(),
        )
    }

    private fun attributesOf(principal: CoSecPrincipal): Map<String, List<String>> {
        val attributes = LinkedHashMap<String, List<String>>()
        principal.roles.takeIf { it.isNotEmpty() }?.let { attributes[ROLES] = it.sorted() }
        principal.policies.takeIf { it.isNotEmpty() }?.let { attributes[POLICIES] = it.sorted() }
        principal.attributes.forEach { (key, value) ->
            val values = valuesOf(value)
            if (key.startsWith(options.attributePrefix) && key.length > options.attributePrefix.length &&
                values.isNotEmpty()
            ) {
                attributes[key] = values
            }
        }
        return attributes
    }

    companion object {
        /** The attribute of a [Caller] listing its principal's CoSec roles in the scope of its request. */
        const val ROLES: String = "roles"

        /** The attribute of a [Caller] listing its principal's CoSec policies. */
        const val POLICIES: String = "policies"

        /** The Wow tenant of [context]: its CoSec tenant, CoSec's default one being [defaultTenant]. */
        internal fun tenantOf(context: SecurityContext, defaultTenant: String): String =
            context.tenant.tenantId.takeUnless { Tenant.isDefault(it) } ?: defaultTenant

        internal fun valuesOf(value: Any?): List<String> = when (value) {
            null -> emptyList()
            is Iterable<*> -> value.mapNotNull { it?.toString()?.trim() }
            is Array<*> -> value.mapNotNull { it?.toString()?.trim() }
            else -> value.toString().split(',').map(String::trim)
        }.filter(String::isNotEmpty).distinct()
    }
}
