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
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.config.configNotNull

/**
 * Which tenant the aggregates of a type are in (replacing 9.x `@StaticTenantId`): by default the
 * caller's, or the one a cross-scope caller names, unless [fixedTenant] fixes one for the type, which
 * then holds whoever the caller is. Every path that gives a command its tenant decides it through [tenantOf], the one
 * rule: the gateway and the test DSL (`CallerAdmission`), a transformer's target, the commands a
 * saga derives, a timer's and those the command topic delivers; the query admission (`QueryTargets`) decides a read's
 * tenant through it too, and which tenants a caller reaches through [reaches]: one rule for the write and the read
 * side.
 */
@InternalWowApi
fun interface TenantPolicy {
    /** The tenant every aggregate of [type] is in, or null when it is the caller's. */
    fun fixedTenant(type: AggregateType): String?

    /**
     * The tenant a command of [type] is in, sent for the [acting] tenant (the caller's, or the execution's that derived
     * it: the commit's, the receipt's or the job instance's; the scheduling aggregate's for a timer) to the tenant
     * [named], if it names one:
     *
     * - a tenant the same as [acting] is not named: a handler's `CommandRequest.to(event.ref, …)` acting in the event's
     *   tenant names none, as Wow's clients drop it ([me.ahoo.wow.api.client.targetTenantOf]). With no [acting] tenant
     *   known (null), whatever [named] says is named;
     * - the type's fixed tenant when it has one: a named tenant that is another is a [TenantConflictException], refused,
     *   never ignored nor rewritten;
     * - else the named tenant, else [acting]. Whether its sender may name another tenant than its own is checked apart.
     */
    fun tenantOf(type: AggregateType, named: String?, acting: String?): String {
        val fixed = fixedTenant(type) ?: return requireNotNull(named ?: acting) {
            "A command of $type names no tenant and acts for none."
        }
        val asked = named?.takeUnless { it == acting }
        if (asked != null && asked != fixed) {
            throw TenantConflictException(type, fixed, asked)
        }
        return fixed
    }

    /**
     * The tenant [caller] addresses an aggregate of [type] in, with the cross-scope [target] it may name (whether the
     * caller may name one is checked apart), by the one rule ([tenantOf]): a target's tenant is named, whoever names
     * it, the caller's own included, since a request names it on purpose; with none, the caller's tenant is the acting
     * one.
     */
    fun tenantOf(type: AggregateType, caller: Caller, target: TargetScope? = null): String =
        target?.tenantId?.let { tenantOf(type, it, null) } ?: tenantOf(type, null, caller.tenantId)

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

        /**
         * The fixed tenants a configuration names ([fixed]): [tenants] maps the name of a type,
         * `<context>.<aggregate>`, to its tenant, and each name is one of [types], which [whose] says whose they are.
         * A name of no such type, or a blank tenant, is a [WowConfigurationException] of the key [entryKey] or
         * [tenantKey] gives the name: one rule and one wording wherever a fixed tenant is configured, the host's
         * `wow.aggregates.[<name>].tenant` and migrate-data's `tenants`, whose check reads the same fixed tenants
         * the host's configuration states.
         */
        @JvmStatic
        fun configured(
            tenants: Map<String, String>,
            types: Collection<AggregateType>,
            whose: String,
            entryKey: (String) -> String,
            tenantKey: (String) -> String,
        ): TenantPolicy {
            val named = types.associateBy { "${it.context}.${it.name}" }
            return fixed(
                tenants.map { (name, tenant) ->
                    val fix = "Name one of the aggregate types $whose: ${named.keys}."
                    val type = configNotNull(named[name], entryKey(name), fix) {
                        "${tenantKey(name)} names no aggregate type $whose."
                    }
                    configCheck(tenant.isNotBlank(), tenantKey(name), tenant, "Name the tenant.") {
                        "${tenantKey(name)} is blank."
                    }
                    type to tenant
                }.toMap(),
            )
        }
    }
}

/** A cross-scope target named [named] as the tenant of [type], whose tenant is fixed to [fixed]. */
@InternalWowApi
class TenantConflictException(val type: AggregateType, val fixed: String, val named: String) :
    IllegalArgumentException("The tenant of $type is fixed to $fixed; the request names $named.")
