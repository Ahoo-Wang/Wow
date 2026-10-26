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
import me.ahoo.wow.query.EventsMode
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.ScopeQueryPolicy
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.queryTenantOf
import me.ahoo.wow.query.schema.EventStreamFields
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.tenant.TenantPolicy

/**
 * The events model's visibility ([EventVisibility], reviews A3-11 and R2.3-6, decisions D254, D259) as a step of the
 * policy chain: while a [pinned][me.ahoo.wow.query.EventsMode.PINNED] policy applies, an aggregate the snapshot
 * queries of its type hide from the caller has no streams for it, whichever entry asks.
 *
 * - Events queries of a type no pinned policy applies to ([EventVisibility.pins]) are not restricted: the
 *   [pushed-down][me.ahoo.wow.query.EventsMode.PUSHDOWN] policies' filters on the streams are the whole rule.
 * - Otherwise the query must name its aggregates by an equality or `IN` on `aggregateId` (or `ID`, `IDS`,
 *   `AGGREGATE_ID`, `AGGREGATE_IDS`) among the conjuncts of its filter, with a tenant ([tenants], else a `tenantId`
 *   equality of the filter): at most the entry budget's `maxFilterValues` ([QueryBudget.DEFAULT_MAX_FILTER_VALUES]
 *   without a limit) when [EventVisibility.batches] checks them with one snapshot query, else
 *   [EventVisibility.POINT_READS]. The restriction is the visible ones, or nothing when none is. A query that does
 *   not name them is rejected (`wow.query.invalid`, `STORAGE_UNSUPPORTED` on `aggregateId`): the rows a pinned policy
 *   hides have no condition on the event streams. The model's descriptor lists the constraint
 *   [AGGREGATE_REQUIRED][UnpinnedEventsPolicy.AGGREGATE_REQUIRED].
 *
 * Other models are not restricted. It runs right after the caller's scope ([ScopeQueryPolicy]).
 */
@InternalWowApi
class EventVisibilityPolicy(
    private val visibility: EventVisibility,
    private val tenants: TenantPolicy = TenantPolicy.CALLERS,
    private val entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) : QueryPolicy {
    override val order: Int = ScopeQueryPolicy.ORDER + 1

    /** It is the pinned policies' rule on the events model, never a condition of its own on the streams. */
    override val events: EventsMode
        get() = EventsMode.NONE

    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        if (context.schema.model != QueryModel.EVENTS) return MatchAllFilter
        val type = context.aggregateType
        if (!visibility.pins(type)) return MatchAllFilter
        val limit = if (visibility.batches(type)) {
            entryPolicy.budget(context.entry)?.maxFilterValues?.takeIf { it > 0 } ?: QueryBudget.DEFAULT_MAX_FILTER_VALUES
        } else {
            EventVisibility.POINT_READS
        }
        val filter = (context.query as FilterCapable<*>).filter
        val conjuncts = EventFilters.conjuncts(filter)
        val named = EventFilters.aggregates(conjuncts)
        val ids = named?.takeIf { it.size <= limit } ?: throw QueryViolation.StorageUnsupported(
            "an events query that names no aggregate (at most $limit) while a pinned query policy applies: " +
                "name its aggregateId",
            EventFilters.AGGREGATE_ID,
        ).rejection()
        val tenantId = tenants.queryTenantOf(type, caller) ?: tenant(conjuncts) ?: throw QueryViolation.StorageUnsupported(
            "an events query without a tenant: name its tenantId",
            TENANT_ID,
        ).rejection()
        val visible = visibility.visible(type, tenantId, ids, caller)
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

    private companion object {
        val TENANT_ID = QueryField(EventStreamFields.TENANT_ID)
    }
}
