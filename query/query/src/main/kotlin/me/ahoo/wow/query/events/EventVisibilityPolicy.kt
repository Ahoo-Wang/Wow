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

package me.ahoo.wow.query.events

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.QueryTenantPolicy
import me.ahoo.wow.query.ScopeQueryPolicy
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.schema.EventStreamFields
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryViolation

/**
 * The events model's visibility ([EventVisibility], reviews A3-11 and R2.3-6) as a step of the policy chain: an
 * aggregate the snapshot queries of its type hide from the caller has no streams for it, whichever entry asks.
 *
 * - A caller who [sees all][EventVisibility.seesAll] the type's aggregates (no snapshot gateway, no policy that could
 *   hide one) is not restricted.
 * - Otherwise the query must name its aggregates, at most [MAX_AGGREGATES] of them, by an equality or `IN` on
 *   `aggregateId` (or `ID`, `IDS`, `AGGREGATE_ID`, `AGGREGATE_IDS`) among the conjuncts of its filter, with a tenant
 *   ([tenants], else a `tenantId` equality of the filter): the restriction is the visible ones, or nothing when none
 *   is. A query that does not name them is rejected (`STORAGE_UNSUPPORTED`): its aggregates cannot be checked one by
 *   one, and the rows a snapshot policy hides have no condition on the event streams.
 *
 * Other models are not restricted. It runs right after the caller's scope ([ScopeQueryPolicy]).
 */
@InternalWowApi
class EventVisibilityPolicy(
    private val visibility: EventVisibility,
    private val tenants: QueryTenantPolicy = QueryTenantPolicy.CALLERS,
) : QueryPolicy {
    override val order: Int = ScopeQueryPolicy.ORDER + 1

    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        if (context.schema.model != QueryModel.EVENTS) return MatchAllFilter
        val type = context.aggregateType
        if (visibility.seesAll(type, caller)) return MatchAllFilter
        val filter = (context.query as FilterCapable<*>).filter
        val conjuncts = EventFilters.conjuncts(filter)
        val ids = EventFilters.aggregates(conjuncts)?.takeIf { it.size <= MAX_AGGREGATES } ?: throw QueryViolation.StorageUnsupported(
            "an events query that names no aggregate (at most $MAX_AGGREGATES) while snapshot query policies may " +
                "hide some: name its aggregateId",
            EventFilters.AGGREGATE_ID,
        ).rejection()
        val tenantId = tenants.tenantOf(type, caller) ?: tenant(conjuncts) ?: throw QueryViolation.StorageUnsupported(
            "an events query without a tenant: name its tenantId",
            TENANT_ID,
        ).rejection()
        val visible = ids.filter { visibility.visible(type.ref(tenantId, it), caller) }
        return when (visible.size) {
            0 -> MatchNoneFilter
            1 -> AggregateIdFilter(visible.single())
            else -> AggregateIdsFilter(visible)
        }
    }

    private fun tenant(conjuncts: List<FilterExpression>): String? = conjuncts.firstNotNullOfOrNull { conjunct ->
        when (conjunct) {
            is TenantIdFilter -> conjunct.value
            is EqualFilter -> (conjunct.value as? String)?.takeIf { conjunct.field == TENANT_ID }
            else -> null
        }
    }

    @InternalWowApi
    companion object {
        /** The most aggregates one events query may name while each must be checked. */
        const val MAX_AGGREGATES: Int = 100

        private val TENANT_ID = QueryField(EventStreamFields.TENANT_ID)
    }
}
