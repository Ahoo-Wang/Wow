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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope

/**
 * Which tenant the aggregates of a type are in (decision D116, replacing 9.x `@StaticTenantId`): by default the
 * caller's, or the one a cross-scope caller names (decision D95), unless [fixedTenant] fixes one for the type, which
 * then holds whoever the caller is. Admission ([CommandGateway]) and the state reads of the edge both decide the
 * tenant through [tenantOf]; the cross-scope rules are unchanged.
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

/** A cross-scope target named [named] as the tenant of [type], whose tenant is fixed to [fixed] (decision D116). */
@InternalWowApi
class TenantConflictException(val type: AggregateType, val fixed: String, val named: String) :
    IllegalArgumentException("The tenant of $type is fixed to $fixed; the request names $named.")
