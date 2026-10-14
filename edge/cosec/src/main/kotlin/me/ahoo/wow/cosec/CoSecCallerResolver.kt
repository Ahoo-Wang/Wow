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
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * What [CoSecCallerResolver] takes from the host (`wow.tenant.default` and `wow.cosec.*`).
 *
 * - [defaultTenant] is the tenant of a principal in CoSec's default tenant, `(0)` (decision D97).
 * - A principal acts across scopes ([Caller.crossScope], decision D95) when it has one of [crossScopeRoles] or one of
 *   [crossScopePolicies], compared whole (`admin@s1`, a role in space `s1`, is not `admin`).
 * - A principal is a tenant-level caller (decision D189) when it has one of [tenantRoles] or one of
 *   [tenantPolicies], compared whole: its caller has no owner, so it reads and writes every aggregate of its tenant,
 *   each action still authorized by CoSec. Any other principal is an owner-level caller, its own owner.
 * - The principal's attributes whose key starts with [attributePrefix] are its ABAC attributes.
 * - A principal is a service identity (decision D193) when it has one of [serviceRoles] or one of [servicePolicies],
 *   compared whole: another service of the system, authenticated by the token CoSec issued to its service account,
 *   which alone may assert the root command of a synchronous call (`Wow-Call`).
 */
data class CoSecCallerOptions(
    val defaultTenant: String = Caller.DEFAULT_TENANT,
    val crossScopeRoles: Set<String> = emptySet(),
    val crossScopePolicies: Set<String> = emptySet(),
    val attributePrefix: String = ABAC_PREFIX,
    val tenantRoles: Set<String> = emptySet(),
    val tenantPolicies: Set<String> = emptySet(),
    val serviceRoles: Set<String> = emptySet(),
    val servicePolicies: Set<String> = emptySet(),
) {
    init {
        require(defaultTenant.isNotBlank()) { "The default tenant is not blank." }
        require(attributePrefix.isNotBlank()) { "The ABAC attribute prefix is not blank." }
    }

    companion object {
        /** The prefix of the principal attributes that are ABAC attributes, as 9.x wow-cosec reads them. */
        const val ABAC_PREFIX: String = "abac."
    }
}

/**
 * The caller of a request from the security context CoSec's WebFilter resolved for it (decision D93): CoSec
 * authenticates and authorizes the request by path and method, this resolver turns its principal into a [Caller].
 *
 * - **Unauthenticated**: no security context, or an anonymous principal, is no caller: the edge answers 401.
 * - **Tenant**: the security context's; CoSec's default tenant `(0)` is [CoSecCallerOptions.defaultTenant].
 * - **Owner and subject**: the principal's id, the subject always. The owner is the principal's id for an owner-level
 *   caller, and none for a tenant-level one ([CoSecCallerOptions.tenantRoles], [CoSecCallerOptions.tenantPolicies];
 *   decision D189), such as an employee who ships every buyer's orders: the parts absent from a caller's scope are
 *   not checked (decisions D94, D129).
 * - **Space** (decision D96): CoSec has no list of a principal's spaces; its spaced roles (`role@space`) are the
 *   spaces it belongs to. `Wow-Space` selects one of them; without it, the principal acts in its only space, or in
 *   none when it belongs to none or to several. A `Wow-Space` the principal does not belong to leaves the caller in
 *   that default, so the edge answers 403 `wow.auth.forbidden`.
 * - **Cross scope**: [CoSecCallerOptions.crossScopeRoles] and [CoSecCallerOptions.crossScopePolicies].
 * - **Service identity** (decision D193): [CoSecCallerOptions.serviceRoles] and [CoSecCallerOptions.servicePolicies];
 *   no other principal is one, whatever headers its request carries.
 * - **Attributes**: the principal's roles ([ROLES]), its policies ([POLICIES]), and its ABAC attributes, keyed with
 *   their prefix (`abac.dept`); a value is a list, an array or a comma-separated string.
 */
class CoSecCallerResolver(private val options: CoSecCallerOptions = CoSecCallerOptions()) : CallerResolver {
    override suspend fun resolve(request: ServerRequest): Caller? {
        val context = request.exchange().getSecurityContext() ?: return null
        return callerOf(context, request.headers().firstHeader(WowHeaders.SPACE))
    }

    /** The caller of [context], acting in [requestedSpace] when its principal belongs to it; null when anonymous. */
    fun callerOf(context: SecurityContext, requestedSpace: String? = null): Caller? {
        val principal = context.principal
        if (principal.anonymous || !principal.authenticated) {
            return null
        }
        val tenant = context.tenant.tenantId.takeUnless { Tenant.isDefault(it) } ?: options.defaultTenant
        val tenantLevel = principal.roles.any { it in options.tenantRoles } ||
            principal.policies.any { it in options.tenantPolicies }
        return Caller(
            tenantId = tenant,
            scope = Scope(
                ownerId = principal.id.takeUnless { tenantLevel },
                spaceId = spaceOf(principal, requestedSpace?.trim()),
            ),
            subject = principal.id,
            crossScope = principal.roles.any { it in options.crossScopeRoles } ||
                principal.policies.any { it in options.crossScopePolicies },
            attributes = attributesOf(principal),
            service = principal.roles.any { it in options.serviceRoles } ||
                principal.policies.any { it in options.servicePolicies },
        )
    }

    private fun spaceOf(principal: CoSecPrincipal, requested: String?): String? {
        val spaces = spacesOf(principal)
        if (!requested.isNullOrEmpty() && requested in spaces) {
            return requested
        }
        return spaces.singleOrNull()
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
        /** The attribute of a [Caller] listing its principal's CoSec roles. */
        const val ROLES: String = "roles"

        /** The attribute of a [Caller] listing its principal's CoSec policies. */
        const val POLICIES: String = "policies"

        /** The spaces [principal] belongs to: those of its spaced roles (`role@space`). */
        @JvmStatic
        fun spacesOf(principal: CoSecPrincipal): Set<String> = principal.roles
            .map { with(SpacedRoleId) { it.toSpacedRoleId() }.spaceId.trim() }
            .filter(String::isNotEmpty)
            .toSortedSet()

        internal fun valuesOf(value: Any?): List<String> = when (value) {
            null -> emptyList()
            is Iterable<*> -> value.mapNotNull { it?.toString()?.trim() }
            is Array<*> -> value.mapNotNull { it?.toString()?.trim() }
            else -> value.toString().split(',').map(String::trim)
        }.filter(String::isNotEmpty).distinct()
    }
}
