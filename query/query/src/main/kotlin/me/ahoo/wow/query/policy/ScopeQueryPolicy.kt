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

package me.ahoo.wow.query.policy

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CrossTenant
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.admission.QueryTargets
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.dataset.DatasetQueryModelProfile
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.query.EventStreamFields
import me.ahoo.wow.spi.tenant.TenantPolicy
import me.ahoo.wow.manifest.Ownership as ManifestOwnership

/**
 * Who each aggregate type belongs to besides its tenant, as the Manifest's
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
 * The caller's scope as a [QueryPolicy], appended at admission step 2 to every query:
 *
 * - the tenant [tenants] gives the caller, when it gives one; else, for a [cross-tenant][QueryCaller.crossTenant]
 *   caller, its tenants (none for every tenant): a type whose tenant is fixed keeps its own;
 * - an owner equality when the caller's [principal][QueryCaller.principal] names an owner (an owner-level caller)
 *   and [ownerships] says the aggregate type belongs to its owner, and a space equality when it names a space: a
 *   tenant-level caller, whose principal names no owner, reads every aggregate of its tenant. This is the default
 *   access rule of an aggregate: one with an access policy of its own ([AccessPolicies.governs]), and a dataset whose
 *   rows are such an aggregate's, read by that policy instead ([AccessQueryPolicy]). On the events, the owner and
 *   space compared are the aggregate's current ones each stream records among its access attributes (`access.owner`,
 *   `access.space`), not those of its commit: an aggregate's history shows to its owner of now, and a stream not yet
 *   stamped to no owner or space;
 * - the deletion scope the model defaults to (a snapshot query sees active aggregates) unless the query states its
 *   own deletion filter. Admission's step 3 would add the same default; stating it here keeps the whole scope one
 *   audited policy.
 *
 * An HTTP caller is first checked again against the read side's rules over [tenants] and [crossTenant]
 * ([QueryTargets.recheck]), whoever built it. A caller without a tenant, owner or space (trusted in-process code) is restricted by none of them. A model that does
 * not carry one of these fields rejects the filter at validation, so the scope fails closed rather than being dropped.
 */
@InternalWowApi
class ScopeQueryPolicy(
    private val tenants: TenantPolicy = TenantPolicy.CALLERS,
    override val order: Int = ORDER,
    private val ownerships: QueryOwnership = QueryOwnership.OWNERS,
    crossTenant: Boolean = false,
    private val access: AccessPolicies = AccessPolicies.NONE,
) : QueryPolicy {
    /** The read side's scope rules, which an HTTP caller is checked against again. */
    private val targets = QueryTargets(tenants, crossTenant)

    /** Each stream records its tenant, and its aggregate's current owner and space. */
    override val events: EventsMode
        get() = EventsMode.PUSHDOWN

    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        targets.recheck(context.aggregateType, caller)
        val owned = ownerships.ownershipOf(context.aggregateType) == Ownership.OWNER
        // An access policy of the aggregate's own decides its owner and space instead.
        val scoped = !access.governs(context.aggregateType) &&
            (context.schema.profile as? DatasetQueryModelProfile)?.dataset?.let(access::governs) != true
        val restrictions = listOfNotNull(
            tenants.queryTenantOf(context.aggregateType, caller)?.let(::TenantIdFilter) ?: caller.crossTenant?.filter(),
            caller.principal.ownerId?.takeIf { owned && scoped }?.let { owner(context, it) },
            caller.principal.spaceId?.takeIf { scoped }?.let { space(context, it) },
            context.schema.profile?.defaultScope(context.filter)?.takeUnless { it === MatchAllFilter },
        )
        return when (restrictions.size) {
            0 -> MatchAllFilter
            1 -> restrictions.single()
            else -> AndFilter(restrictions)
        }
    }

    /** The owner equality on [context]'s model: on the events, the stream's current owner, an access attribute. */
    private fun owner(context: QueryContext<*>, ownerId: String): FilterExpression =
        if (context.schema.model == QueryModel.EVENTS) EqualFilter(STREAM_OWNER, ownerId) else OwnerIdFilter(ownerId)

    /** The space equality on [context]'s model: on the events, the stream's current space, an access attribute. */
    private fun space(context: QueryContext<*>, spaceId: String): FilterExpression =
        if (context.schema.model == QueryModel.EVENTS) EqualFilter(STREAM_SPACE, spaceId) else SpaceIdFilter(spaceId)

    @InternalWowApi
    companion object {
        /** Before every other policy, so a scope failure is reported first. */
        const val ORDER: Int = Int.MIN_VALUE

        private val STREAM_OWNER = QueryField("${EventStreamFields.ACCESS}.${AccessValues.OWNER}")
        private val STREAM_SPACE = QueryField("${EventStreamFields.ACCESS}.${AccessValues.SPACE}")
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

/**
 * The tenant [caller]'s queries of [type] are restricted to: the type's fixed tenant,
 * as a command or a state read of it is, else the caller's; null for a caller bound to no tenant.
 */
internal fun TenantPolicy.queryTenantOf(type: AggregateType, caller: QueryCaller): String? =
    fixedTenant(type) ?: caller.tenantId
