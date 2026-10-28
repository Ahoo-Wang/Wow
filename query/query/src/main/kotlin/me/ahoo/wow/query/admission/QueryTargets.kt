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

package me.ahoo.wow.query.admission

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.spi.tenant.TenantConflictException
import me.ahoo.wow.spi.tenant.TenantPolicy

/**
 * Where a read decides the tenant and scope its caller reads in: the one home of the read side's
 * scope authorization, which every edge asks instead of deciding it itself. A query route, a state read and the
 * dataset listing all read through [of].
 *
 * - A caller names another scope (`Wow-Scope`) only when it may act across scopes
 *   ([Caller.crossScope]); any other caller naming one is refused, [QueryErrors.FORBIDDEN].
 * - A cross-tenant read is refused while [crossTenant] is off (`wow.query.cross-tenant.enabled`),
 *   [QueryErrors.FORBIDDEN] too.
 * - The tenant follows [tenants]: a type whose tenant is fixed is read in that tenant alone, so a
 *   cross-tenant read of it is narrowed to it, and one whose tenants leave it out, or a target naming another
 *   tenant, is refused, [QueryErrors.MALFORMED].
 */
@InternalWowApi
class QueryTargets(
    private val tenants: TenantPolicy = TenantPolicy.CALLERS,
    private val crossTenant: Boolean = false,
) {
    /**
     * The query caller [caller] reads [type] as on [entry], in the [target] scope and across the [tenants][reads] it
     * names, when it names them: the caller's identity acting in the decided tenant and scope, carrying the [target]
     * and, for a cross-tenant read, the tenants it reads. Throws the [QueryError] that refuses it.
     */
    fun of(
        type: AggregateType,
        caller: Caller,
        target: TargetScope? = null,
        reads: CrossTenant? = null,
        entry: QueryEntry = QueryEntry.HTTP,
    ): QueryCaller {
        admit(caller, target, reads)
        val scope = target?.scope ?: caller.scope
        if (reads != null) {
            val fixed = tenants.fixedTenant(type)
            if (fixed != null && !reads.covers(fixed)) {
                throw QueryTargetException(QueryErrors.MALFORMED, "The tenant of $type is fixed.")
            }
            // A fixed tenant narrows the read to itself, whatever else the caller names.
            val narrowed = fixed?.let { CrossTenant(setOf(it)) } ?: reads
            return QueryCaller(caller.copyWith(scope = scope), entry, target = target, crossTenant = narrowed)
        }
        val tenant = try {
            tenants.tenantOf(type, caller, target)
        } catch (conflict: TenantConflictException) {
            throw QueryTargetException(QueryErrors.MALFORMED, conflict.message!!)
        }
        return QueryCaller(caller.copyWith(tenantId = tenant, scope = scope), entry, target = target)
    }

    /**
     * The caller [caller] acts as on a route that reads no aggregate type of its own, such as a service route: in the
     * [target] scope and its tenant when it names one and may act across scopes, else in its own. Throws the
     * [QueryErrors.FORBIDDEN] [QueryError] that refuses it.
     */
    fun acting(caller: Caller, target: TargetScope? = null): Caller {
        admit(caller, target)
        return caller.copyWith(tenantId = target?.tenantId ?: caller.tenantId, scope = target?.scope ?: caller.scope)
    }

    /**
     * Whether [caller] may name [target] and the tenants it [reads] at all, whatever it reads: only a caller that may
     * act across scopes names either, and the tenants only while cross-tenant queries are on. Throws the
     * [QueryErrors.FORBIDDEN] [QueryError] that refuses it.
     */
    fun admit(caller: Caller, target: TargetScope? = null, reads: CrossTenant? = null) {
        if ((target != null || reads != null) && !caller.crossScope) {
            throw QueryTargetException(QueryErrors.FORBIDDEN, "The caller may not read in another scope.")
        }
        if (reads != null && !crossTenant) {
            throw QueryTargetException(
                QueryErrors.FORBIDDEN,
                "Cross-tenant queries are off (wow.query.cross-tenant.enabled).",
            )
        }
    }

    /**
     * Checks again, as the engine admits [caller]'s read of [type], what [of] decided for an
     * [HTTP][QueryEntry.HTTP] caller: the [target][QueryCaller.target] and the
     * [tenants it reads][QueryCaller.crossTenant] are refused unless its identity may name them ([admit]), and a type
     * whose tenant is fixed is read in it alone. A caller [of] built passes; one built otherwise reaches no further. An
     * in-process caller is trusted by its entry.
     */
    fun recheck(type: AggregateType, caller: QueryCaller) {
        if (caller.entry != QueryEntry.HTTP) {
            return
        }
        val target = caller.target
        val reads = caller.crossTenant
        val identity = caller.caller
        if (identity == null) {
            if (target != null || reads != null) {
                throw QueryTargetException(QueryErrors.FORBIDDEN, "The caller may not read in another scope.")
            }
            return
        }
        admit(identity, target, reads)
        val fixed = tenants.fixedTenant(type) ?: return
        val outside = if (reads != null) !reads.covers(fixed) else target?.tenantId.let { it != null && it != fixed }
        if (outside) {
            throw QueryTargetException(QueryErrors.MALFORMED, "The tenant of $type is fixed.")
        }
    }

    @InternalWowApi
    companion object {
        /** Every type in its caller's tenant, and no cross-tenant read. */
        @JvmField
        val DEFAULT: QueryTargets = QueryTargets()
    }
}

/** A read [QueryTargets] refuses: the scope or the tenants it names. */
@InternalWowApi
class QueryTargetException(override val errorCode: String, override val errorMsg: String) :
    RuntimeException(errorMsg), QueryError
