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

package me.ahoo.wow.query

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.filter.QueryContext

/**
 * Which tenant a caller's queries of an aggregate type are restricted to (decision D129): the port through which the
 * host's `TenantPolicy` (decision D116, the one state reads use) decides it, so a query sees the tenant a command
 * would act in. The host implements it in 6.2c, typically as the type's fixed tenant when it has one, else
 * [QueryCaller.tenantId].
 */
fun interface QueryTenantPolicy {
    /** The tenant [caller]'s queries of [type] are restricted to, or `null` to restrict them to none. */
    fun tenantOf(type: AggregateType, caller: QueryCaller): String?

    companion object {
        /** Every query is restricted to its caller's tenant; a caller without one is restricted to none. */
        @JvmField
        val CALLERS: QueryTenantPolicy = QueryTenantPolicy { _, caller -> caller.tenantId }
    }
}

/**
 * The caller's scope as a [QueryPolicy] (decision D129), appended at admission step 2 to every query:
 *
 * - the tenant [tenants] gives the caller, when it gives one;
 * - an owner equality when the caller's [principal][QueryCaller.principal] names an owner, and a space equality when
 *   it names a space;
 * - the deletion scope the model defaults to (a snapshot query sees active aggregates) unless the query states its
 *   own deletion filter. Admission's step 3 would add the same default; stating it here keeps the whole scope one
 *   audited policy.
 *
 * A caller without a tenant, owner or space (trusted in-process code) is restricted by none of them. A model that does
 * not carry one of these fields rejects the filter at validation, so the scope fails closed rather than being dropped.
 */
class ScopeQueryPolicy(
    private val tenants: QueryTenantPolicy = QueryTenantPolicy.CALLERS,
    override val order: Int = ORDER,
) : QueryPolicy {
    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        val restrictions = listOfNotNull(
            tenants.tenantOf(context.aggregateType, caller)?.let(::TenantIdFilter),
            caller.principal.ownerId?.let(::OwnerIdFilter),
            caller.principal.spaceId?.let(::SpaceIdFilter),
            context.schema.profile?.defaultScope(context.filter)?.takeUnless { it === MatchAllFilter },
        )
        return when (restrictions.size) {
            0 -> MatchAllFilter
            1 -> restrictions.single()
            else -> AndFilter(restrictions)
        }
    }

    companion object {
        /** Before every other policy, so a scope failure is reported first. */
        const val ORDER: Int = Int.MIN_VALUE
    }
}

/** The filter of the query being admitted: every gateway operation's query carries one. */
internal val QueryContext<*>.filter: FilterExpression
    get() = (query as FilterCapable<*>).filter
