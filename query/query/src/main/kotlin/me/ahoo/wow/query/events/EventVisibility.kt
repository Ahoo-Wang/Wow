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
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.query.state.StateVisibility

/**
 * Whether a caller sees an aggregate's event streams in the events model ([EventQueryGateways], decision D235); an
 * aggregate it does not see has no streams for it. [EventVisibilityPolicy] applies it at admission step 2.
 */
@InternalWowApi
fun interface EventVisibility {
    /** Whether [caller] sees the event streams of [ref]. */
    suspend fun visible(ref: AggregateRef, caller: QueryCaller): Boolean

    /**
     * Whether [caller] sees the streams of every aggregate of [type] without a check of each: nothing that could hide
     * one applies. An events query that names no aggregate needs this, since its aggregates cannot be checked one by
     * one; none does by default.
     */
    suspend fun seesAll(type: AggregateType, caller: QueryCaller): Boolean = false

    @InternalWowApi
    companion object {
        /** Every aggregate is visible. */
        @JvmField
        val ALL: EventVisibility = object : EventVisibility {
            override suspend fun visible(ref: AggregateRef, caller: QueryCaller): Boolean = true

            override suspend fun seesAll(type: AggregateType, caller: QueryCaller): Boolean = true
        }

        /**
         * An aggregate the snapshot queries of its type hide from the caller is hidden from the events model too
         * (review A3-11): [StateVisibility] of its latest committed state, which [latest] reads, under the snapshot
         * query gateway [snapshots] gives for its type, so ABAC and the application's query policies hide the same
         * aggregates from both models whichever entry asks.
         *
         * [latest] gives the aggregate's latest state: its snapshot, else, before its first snapshot is written, the
         * state its log holds (review R2.3-6), so a new aggregate follows the rule from its first commit instead of
         * showing its streams to every caller of its tenant while its snapshot lags. An aggregate with no committed
         * state (null) shows nothing: it has no streams yet, and one committed between the check and the read is not
         * shown unchecked. A type without a snapshot query gateway has no policies to apply: every aggregate of it is
         * visible, and no state is read.
         *
         * [hides] says whether the snapshot queries may hide an aggregate from a caller of its scope at all: ABAC or an
         * application's query policy is configured. When they cannot, or the type has no snapshot query gateway, a
         * caller [seesAll] the type's aggregates, so an events query need not name its aggregate.
         */
        @JvmOverloads
        fun of(
            snapshots: (AggregateType) -> QueryGateway?,
            hides: Boolean = true,
            latest: suspend (AggregateRef) -> CommittedState?,
        ): EventVisibility = object : EventVisibility {
            override suspend fun visible(ref: AggregateRef, caller: QueryCaller): Boolean {
                val gateway = snapshots(ref.type)
                return gateway == null || latest(ref)?.let { StateVisibility.visible(gateway, caller, it) } == true
            }

            override suspend fun seesAll(type: AggregateType, caller: QueryCaller): Boolean =
                !hides || snapshots(type) == null
        }
    }
}
