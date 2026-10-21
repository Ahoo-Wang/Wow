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
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.manifest.Ownership as ManifestOwnership

/**
 * Which tenant a caller's queries of an aggregate type are restricted to (decision D129): the port through which the
 * host's `TenantPolicy` (decision D116, the one state reads use) decides it, so a query sees the tenant a command
 * would act in. The host implements it in 6.2c, typically as the type's fixed tenant when it has one, else
 * [QueryCaller.tenantId].
 */
@InternalWowApi
fun interface QueryTenantPolicy {
    /** The tenant [caller]'s queries of [type] are restricted to, or `null` to restrict them to none. */
    fun tenantOf(type: AggregateType, caller: QueryCaller): String?

    @InternalWowApi
    companion object {
        /** Every query is restricted to its caller's tenant; a caller without one is restricted to none. */
        @JvmField
        val CALLERS: QueryTenantPolicy = QueryTenantPolicy { _, caller -> caller.tenantId }
    }
}

/**
 * Who each aggregate type belongs to besides its tenant (decision D189), as the Manifest's
 * [AggregateManifest.ownership] says: [ScopeQueryPolicy] restricts a caller's queries by its owner only for an
 * [Ownership.OWNER] aggregate.
 */
@InternalWowApi
fun interface QueryOwnership {
    /** The ownership of [type]; [Ownership.OWNER] for a type no Manifest declares. */
    fun ownershipOf(type: AggregateType): Ownership

    @InternalWowApi
    companion object {
        /** Every aggregate belongs to its owner, the default. */
        @JvmField
        val OWNERS: QueryOwnership = QueryOwnership { Ownership.OWNER }

        /** The ownership of every aggregate [manifest] declares, the others [Ownership.OWNER]. */
        @JvmStatic
        fun of(manifest: Manifest): QueryOwnership {
            val tenants = manifest.contexts.flatMap { context ->
                context.aggregates.filter { it.ownership == ManifestOwnership.TENANT }
                    .map { AggregateType(context.name, it.name) }
            }.toSet()
            if (tenants.isEmpty()) {
                return OWNERS
            }
            return QueryOwnership { type -> if (type in tenants) Ownership.TENANT else Ownership.OWNER }
        }
    }
}

/**
 * The caller's scope as a [QueryPolicy] (decision D129), appended at admission step 2 to every query:
 *
 * - the tenant [tenants] gives the caller, when it gives one; else, for a [cross-tenant][QueryCaller.crossTenant]
 *   caller, its tenants (none for every tenant): a type whose tenant is fixed keeps its own;
 * - an owner equality when the caller's [principal][QueryCaller.principal] names an owner (an owner-level caller,
 *   decision D189) and [ownerships] says the aggregate type belongs to its owner, and a space equality when it names
 *   a space: a tenant-level caller, whose principal names no owner, reads every aggregate of its tenant;
 * - the deletion scope the model defaults to (a snapshot query sees active aggregates) unless the query states its
 *   own deletion filter. Admission's step 3 would add the same default; stating it here keeps the whole scope one
 *   audited policy.
 *
 * A caller without a tenant, owner or space (trusted in-process code) is restricted by none of them. A model that does
 * not carry one of these fields rejects the filter at validation, so the scope fails closed rather than being dropped.
 */
@InternalWowApi
class ScopeQueryPolicy(
    private val tenants: QueryTenantPolicy = QueryTenantPolicy.CALLERS,
    override val order: Int = ORDER,
    private val ownerships: QueryOwnership = QueryOwnership.OWNERS,
) : QueryPolicy {
    /** The tenant, owner and space are recorded with each stream at its commit (decision D56). */
    override val events: EventsMode
        get() = EventsMode.PUSHDOWN

    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        val owned = ownerships.ownershipOf(context.aggregateType) == Ownership.OWNER
        val restrictions = listOfNotNull(
            tenants.tenantOf(context.aggregateType, caller)?.let(::TenantIdFilter) ?: caller.crossTenant?.filter(),
            caller.principal.ownerId?.takeIf { owned }?.let(::OwnerIdFilter),
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

/** The tenants this reads as a filter: null for every tenant. */
private fun CrossTenant.filter(): FilterExpression? {
    val tenants = tenantIds?.map(::TenantIdFilter) ?: return null
    return tenants.singleOrNull() ?: OrFilter(tenants)
}

/** The filter of the query being admitted: every gateway operation's query carries one. */
internal val QueryContext<*>.filter: FilterExpression
    get() = (query as FilterCapable<*>).filter
