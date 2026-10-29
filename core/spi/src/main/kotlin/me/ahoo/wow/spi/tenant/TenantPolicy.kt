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

package me.ahoo.wow.spi.tenant

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope

/**
 * Which tenant the aggregates of a type are in (replacing 9.x `@StaticTenantId`): by default the
 * caller's, or the one a cross-scope caller names, unless [fixedTenant] fixes one for the type, which
 * then holds whoever the caller is. The command admission of the runtime and the query admission (`QueryTargets`) both
 * decide the tenant through [tenantOf], and which tenants a caller reaches through [reaches]: one rule for the write
 * and the read side.
 */
@InternalWowApi
fun interface TenantPolicy {
    /** The tenant every aggregate of [type] is in, or null when it is the caller's. */
    fun fixedTenant(type: AggregateType): String?

    /**
     * The tenant [caller] addresses an aggregate of [type] in, with the cross-scope [target] it may name (whether the
     * caller may name one is checked apart): the type's fixed tenant, else the target's, else the caller's. A target
     * that names another tenant than the type's fixed one is a [TenantConflictException]: it is refused, not ignored.
     */
    fun tenantOf(type: AggregateType, caller: Caller, target: TargetScope? = null): String {
        val fixed = fixedTenant(type) ?: return target?.tenantId ?: caller.tenantId
        val named = target?.tenantId
        if (named != null && named != fixed) {
            throw TenantConflictException(type, fixed, named)
        }
        return fixed
    }

    @InternalWowApi
    companion object {
        /**
         * Whether [caller] reaches what is in [tenantId], whatever tenant a type is fixed to: its own
         * tenant, and every tenant for a caller that may act across scopes.
         */
        @JvmStatic
        fun reaches(caller: Caller, tenantId: String): Boolean = caller.crossScope || caller.tenantId == tenantId

        /**
         * The one tenant [caller] reaches ([reaches]): its own; null for a caller that may act across scopes, which
         * reaches every tenant. A read that a store filters by tenant takes it.
         */
        @JvmStatic
        fun confinedTo(caller: Caller): String? = caller.tenantId.takeUnless { caller.crossScope }

        /** Every aggregate is in its caller's tenant. */
        @JvmField
        val CALLERS: TenantPolicy = TenantPolicy { null }

        /** The aggregates of each type of [tenants] are in its tenant, those of the other types in their caller's. */
        @JvmStatic
        fun fixed(tenants: Map<AggregateType, String>): TenantPolicy {
            tenants.forEach { (type, tenant) -> require(tenant.isNotBlank()) { "The fixed tenant of $type is blank." } }
            if (tenants.isEmpty()) {
                return CALLERS
            }
            val fixed = tenants.toMap()
            return TenantPolicy { fixed[it] }
        }
    }
}

/** A cross-scope target named [named] as the tenant of [type], whose tenant is fixed to [fixed]. */
@InternalWowApi
class TenantConflictException(val type: AggregateType, val fixed: String, val named: String) :
    IllegalArgumentException("The tenant of $type is fixed to $fixed; the request names $named.")
