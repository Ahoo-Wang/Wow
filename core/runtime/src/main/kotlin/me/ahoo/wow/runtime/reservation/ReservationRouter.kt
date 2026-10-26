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

package me.ahoo.wow.runtime.reservation

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import kotlin.time.Duration

/**
 * The reservations of a context whose aggregates are in several log groups (decision D307): an aggregate type's
 * reservations follow its log, so each call goes to the reservation store of the scope's type's group, [groups] for
 * the types they name, [default] for the others. Uniqueness is within a type ([ReservationScope.type]), so no key is
 * ever looked up in two stores.
 */
internal class ReservationRouter(
    private val default: ReservationStore?,
    private val groups: Map<AggregateType, ReservationStore>,
) : ReservationStore {
    /** The reservation store of [type]'s group. */
    fun storeOf(type: AggregateType): ReservationStore =
        groups[type] ?: checkNotNull(default) { "The log group of $type has no ReservationStore." }

    override suspend fun reserve(
        scope: ReservationScope,
        key: String,
        owner: AggregateRef,
        hold: String,
        ttl: Duration,
    ): Reservation? = storeOf(scope.type).reserve(scope, key, owner, hold, ttl)

    override suspend fun abandon(scope: ReservationScope, key: String, owner: AggregateRef, hold: String): Boolean =
        storeOf(scope.type).abandon(scope, key, owner, hold)

    override suspend fun confirm(scope: ReservationScope, key: String, owner: AggregateRef, version: Int): ConfirmOutcome =
        storeOf(scope.type).confirm(scope, key, owner, version)

    override suspend fun release(scope: ReservationScope, key: String, owner: AggregateRef, version: Int): Boolean =
        storeOf(scope.type).release(scope, key, owner, version)

    override suspend fun find(scope: ReservationScope, key: String): Reservation? = storeOf(scope.type).find(scope, key)

    override fun toString(): String = "ReservationRouter(default=$default, groups=$groups)"

    companion object {
        /**
         * The reservations of a context over its [default] group's store and its other [groups]' stores: [default]
         * itself when no group has a store of its own, null when no group has one at all.
         */
        fun of(default: ReservationStore?, groups: Map<AggregateType, ReservationStore>): ReservationStore? {
            val own = groups.filterValues { it !== default }
            return if (own.isEmpty()) default else ReservationRouter(default, own)
        }
    }
}
