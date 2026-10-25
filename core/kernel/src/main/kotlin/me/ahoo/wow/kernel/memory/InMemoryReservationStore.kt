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

package me.ahoo.wow.kernel.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import java.time.Clock
import kotlin.time.Duration

/**
 * A [ReservationStore] in memory, for tests and samples: one entry per `(context, tenant, namespace, key)`. Every
 * operation is serialized by one lock; expiry follows [clock].
 */
@InternalWowApi
class InMemoryReservationStore(private val clock: Clock = Clock.systemUTC()) : ReservationStore {
    private val lock = Mutex()
    private val reservations = HashMap<Pair<ReservationScope, String>, Reservation>()

    override suspend fun reserve(
        scope: ReservationScope,
        key: String,
        owner: AggregateRef,
        hold: String,
        ttl: Duration,
    ): Reservation? {
        require(ttl.isPositive()) { "A reservation's time to live is positive." }
        require(hold.isNotBlank()) { "A reservation's hold is not blank." }
        require(owner.type.context == scope.context) { "$owner reserves keys of its own context only." }
        return lock.withLock {
            val now = clock.millis()
            val expiresAt = now + ttl.inWholeMilliseconds
            val held = reservations[scope to key]
            when {
                held == null || !held.isLive(now) -> Reservation(scope, key, owner, expiresAt, setOf(hold))
                held.owner != owner -> return@withLock null
                held.confirmed -> return@withLock held
                else -> held.copyWith(expiresAt = maxOf(held.expiresAt!!, expiresAt), holds = held.holds + hold)
            }.also { reservations[scope to key] = it }
        }
    }

    override suspend fun abandon(scope: ReservationScope, key: String, owner: AggregateRef, hold: String): Boolean =
        lock.withLock {
            val held = reservations[scope to key]?.takeIf { it.owner == owner && !it.confirmed }
            if (held == null || hold !in held.holds) {
                return@withLock false
            }
            val left = held.holds - hold
            if (left.isEmpty()) {
                reservations.remove(scope to key)
            } else {
                reservations[scope to key] = held.copyWith(holds = left)
            }
            true
        }

    override suspend fun confirm(
        scope: ReservationScope,
        key: String,
        owner: AggregateRef,
        version: Int,
    ): ConfirmOutcome = lock.withLock {
        val held = reservations[scope to key]
        if (held != null && held.owner != owner && held.isLive(clock.millis())) {
            return@withLock ConfirmOutcome.Taken(held)
        }
        val confirmed = maxOf(version, held?.takeIf { it.owner == owner }?.version ?: version)
        reservations[scope to key] = Reservation(scope, key, owner, null, version = confirmed)
        ConfirmOutcome.Confirmed
    }

    override suspend fun release(scope: ReservationScope, key: String, owner: AggregateRef, version: Int): Boolean =
        lock.withLock {
            val held = reservations[scope to key]
            if (held?.owner != owner || held.confirmed && held.version > version) {
                return@withLock false
            }
            reservations.remove(scope to key)
            true
        }

    override suspend fun find(scope: ReservationScope, key: String): Reservation? = lock.withLock {
        reservations[scope to key]?.takeIf { it.isLive(clock.millis()) }
    }
}
