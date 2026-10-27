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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.observe.isolated
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.query.state.StateVisibility

/**
 * Which aggregates' event streams a caller sees in the events model ([EventQueryGateways], decision D235) while a
 * [pinned][me.ahoo.wow.query.policy.EventsMode.PINNED] policy applies; an aggregate it does not see has no streams for it.
 * [EventVisibilityPolicy] applies it at admission step 2.
 */
@InternalWowApi
fun interface EventVisibility {
    /** The aggregates of [ids], of [type] in [tenantId], whose event streams [caller] sees, in the order of [ids]. */
    suspend fun visible(type: AggregateType, tenantId: String, ids: List<String>, caller: QueryCaller): List<String>

    /**
     * Whether the events queries of [type] are pinned: a [pinned][me.ahoo.wow.query.policy.EventsMode.PINNED] policy applies,
     * so each must name its aggregates for [visible] to check them, and its descriptor lists `AGGREGATE_REQUIRED`.
     * Otherwise every caller sees the streams of every aggregate its other policies' filters admit. Pinned by default.
     */
    fun pins(type: AggregateType): Boolean = true

    /**
     * Whether [visible] checks the aggregates of [type] with one snapshot query (decisions D254, D259), so an events
     * query may name as many as its budget's `maxFilterValues`; otherwise each is read on its own, and at most
     * [POINT_READS] of them.
     */
    fun batches(type: AggregateType): Boolean = true

    @InternalWowApi
    companion object {
        /** The most aggregates one events query has read one by one: of a type whose snapshots are not queried. */
        const val POINT_READS: Int = 100

        /** Every aggregate is visible. */
        val ALL: EventVisibility = object : EventVisibility {
            override suspend fun visible(
                type: AggregateType,
                tenantId: String,
                ids: List<String>,
                caller: QueryCaller,
            ): List<String> = ids

            override fun pins(type: AggregateType): Boolean = false
        }

        /**
         * An aggregate the snapshot queries of its type hide from the caller is hidden from the events model too
         * (review A3-11), under the gateway [snapshots] gives for its type, so the caller scope, ABAC and the
         * application's query policies hide the same aggregates from both models whichever entry asks. That gateway is
         * the type's snapshot query gateway, or, for a type whose snapshots are not queried, a
         * [me.ahoo.wow.query.state.SnapshotPolicyGateway] that evaluates the same policies on the state alone (review
         * R3.3-3).
         *
         * - A type whose snapshots are queried ([batched]) is checked with one query of its snapshots
         *   ([QueryGateway.admitAggregates]): `aggregateId IN (...)` read under the store's bulkhead, each snapshot
         *   admitted in memory. An aggregate with no snapshot yet (its first one lags, review R2.3-6) is read as
         *   [latest] gives it, at most [POINT_READS] of them; any further one shows nothing.
         * - Any other type is read one aggregate at a time, as [latest] gives each.
         *
         * [latest] gives the aggregate's latest state: its snapshot, else the state its log holds, so a new aggregate
         * follows the rule from its first commit instead of showing its streams to every caller of its tenant while
         * its snapshot lags. An aggregate with no committed state (null) shows nothing: it has no streams yet, and one
         * committed between the check and the read is not shown unchecked. [observer] counts the aggregates checked
         * each way ([QueryObserver.onEventsPinned]), so a high share of point reads is visible.
         *
         * [hides] says whether a [pinned][me.ahoo.wow.query.policy.EventsMode.PINNED] policy applies at all ([pins]). When
         * none does, an events query need not name its aggregate. When one does, a type [snapshots] gives no gateway for
         * fails closed (review R3.3-3): none of its aggregates is visible, since
         * no policy could be evaluated on it.
         */
        @JvmOverloads
        fun of(
            snapshots: (AggregateType) -> QueryGateway?,
            hides: Boolean = true,
            batched: (AggregateType) -> Boolean = { true },
            observer: QueryObserver = QueryObserver.NONE,
            latest: suspend (AggregateRef) -> CommittedState?,
        ): EventVisibility = object : EventVisibility {
            override suspend fun visible(
                type: AggregateType,
                tenantId: String,
                ids: List<String>,
                caller: QueryCaller,
            ): List<String> {
                val gateway = snapshots(type) ?: return if (hides) emptyList() else ids
                val batch = if (batched(type)) gateway.admitAggregates(tenantId, ids, caller) else null
                val unread = batch?.let { found -> ids.filterNot(found.stored::contains) } ?: ids
                val read = unread.take(POINT_READS).filterTo(mutableSetOf()) { id ->
                    latest(type.ref(tenantId, id))?.let { StateVisibility.visible(gateway, caller, it) } == true
                }
                val batchRead = ids.size - unread.size
                observer.isolated { onEventsPinned(type, batchRead, minOf(unread.size, POINT_READS)) }
                return ids.filter { id -> batch?.visible?.contains(id) == true || id in read }
            }

            override fun pins(type: AggregateType): Boolean = hides

            override fun batches(type: AggregateType): Boolean = batched(type)
        }
    }
}
