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
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.ScopeQueryPolicy
import me.ahoo.wow.query.descriptor.ConstraintDescriptor
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.json.query.EventStreamFields

/**
 * Whether an `events` query that does not pin its aggregates runs (review R3.4-2): on a PostgreSQL or MongoDB log the
 * only indexes are the write store's, so a query that names no `aggregateId` scans the aggregate type's log, on the
 * store the appends write to. Such queries are a capability the log backends declare (decision D235), so they run by
 * default ([ALLOW]), bounded by the entry's budget, the store's [QueryBulkhead][me.ahoo.wow.query.QueryBulkhead] and
 * its read source; a deployment that wants none of them on its write store opts in to [REFUSE], per store or per
 * aggregate type (`wow.query.events.unpinned`, `wow.stores.<name>.query.events.unpinned`,
 * `wow.aggregates.[<context>.<aggregate>].query.events.unpinned`).
 */
@InternalWowApi
enum class UnpinnedEvents {
    /** The default: an events query may filter freely. */
    ALLOW,

    /**
     * An events query must name its aggregates by `aggregateId` (an equality or `IN`, or `ID`, `IDS`, `AGGREGATE_ID`,
     * `AGGREGATE_IDS`, among the conjuncts of its filter); any other is rejected `STORAGE_UNSUPPORTED`, and the model's
     * descriptor lists the constraint [AGGREGATE_REQUIRED][UnpinnedEventsPolicy.AGGREGATE_REQUIRED].
     */
    REFUSE,
}

/**
 * The [UnpinnedEvents.REFUSE] rule as a step of the policy chain: an events query whose filter names no aggregate is
 * rejected `STORAGE_UNSUPPORTED` on `aggregateId`; one that names them is not restricted further. It runs right after
 * the caller's scope ([ScopeQueryPolicy]), before the events' visibility, so a query it refuses reads nothing.
 */
@InternalWowApi
class UnpinnedEventsPolicy : QueryPolicy {
    override val order: Int = ScopeQueryPolicy.ORDER + 1

    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        if (context.schema.model != QueryModel.EVENTS) return MatchAllFilter
        val filter = (context.query as FilterCapable<*>).filter
        if (EventFilters.aggregates(EventFilters.conjuncts(filter)) == null) {
            throw QueryViolation.StorageUnsupported(
                "an events query that names no aggregate on this store: name its aggregateId",
                EventFilters.AGGREGATE_ID,
            ).rejection()
        }
        return MatchAllFilter
    }

    @InternalWowApi
    companion object {
        /**
         * The descriptor's constraint of a model whose queries must name their aggregates: its `fields` are the one
         * field to name them by, `aggregateId`.
         */
        const val AGGREGATE_REQUIRED: String = "AGGREGATE_REQUIRED"

        /**
         * The constraint the descriptor of an events model lists under [UnpinnedEvents.REFUSE], or while a pinned
         * policy applies ([EventVisibilityPolicy]).
         */
        @JvmField
        val CONSTRAINT: ConstraintDescriptor =
            ConstraintDescriptor(AGGREGATE_REQUIRED, fields = listOf(EventStreamFields.AGGREGATE_ID))
    }
}

/** How the events policies read a query's filter: its top-level conjuncts and the aggregates they pin. */
internal object EventFilters {
    val AGGREGATE_ID = QueryField(EventStreamFields.AGGREGATE_ID)

    /** [filter]'s top-level conjuncts, nested ANDs flattened. */
    fun conjuncts(filter: FilterExpression): List<FilterExpression> = when (filter) {
        is AndFilter -> filter.operands.flatMap(::conjuncts)
        else -> listOf(filter)
    }

    /** The aggregate ids every match must have, intersected over [conjuncts]; null when none names any. */
    fun aggregates(conjuncts: List<FilterExpression>): List<String>? = conjuncts.mapNotNull { conjunct ->
        when (conjunct) {
            is IdFilter -> listOf(conjunct.value)
            is AggregateIdFilter -> listOf(conjunct.value)
            is IdsFilter -> conjunct.values
            is AggregateIdsFilter -> conjunct.values
            is EqualFilter -> (conjunct.value as? String)?.takeIf { conjunct.field == AGGREGATE_ID }?.let(::listOf)
            is InFilter -> conjunct.values.takeIf { conjunct.field == AGGREGATE_ID && it.all { id -> id is String } }
                ?.map { it as String }
            else -> null
        }
    }.reduceOrNull { left, right -> left.filter { it in right } }?.distinct()
}
