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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationStore
import java.time.Clock
import kotlin.time.Duration

/**
 * A [ReservationStore] in memory, for tests and samples. Every operation is serialized by one lock; expiry follows
 * [clock].
 */
@InternalWowApi
class InMemoryReservationStore(private val clock: Clock = Clock.systemUTC()) : ReservationStore {
    private val lock = Mutex()
    private val reservations = HashMap<Pair<String, String>, Reservation>()

    override suspend fun reserve(namespace: String, key: String, owner: String, ttl: Duration): Reservation? {
        require(ttl.isPositive()) { "A reservation's time to live is positive." }
        return lock.withLock {
            val now = clock.millis()
            val held = reservations[namespace to key]
            when {
                held != null && held.owner != owner && held.isLive(now) -> null
                held != null && held.owner == owner && held.confirmed -> held
                else -> Reservation(namespace, key, owner, now + ttl.inWholeMilliseconds)
                    .also { reservations[namespace to key] = it }
            }
        }
    }

    override suspend fun confirm(namespace: String, key: String, owner: String): Boolean = lock.withLock {
        val held = reservations[namespace to key]
        if (held?.owner != owner) {
            return false
        }
        reservations[namespace to key] = held.copy(expiresAt = null)
        true
    }

    override suspend fun release(namespace: String, key: String, owner: String): Boolean = lock.withLock {
        if (reservations[namespace to key]?.owner != owner) {
            return false
        }
        reservations.remove(namespace to key)
        true
    }

    override suspend fun find(namespace: String, key: String): Reservation? = lock.withLock {
        reservations[namespace to key]?.takeIf { it.isLive(clock.millis()) }
    }
}
