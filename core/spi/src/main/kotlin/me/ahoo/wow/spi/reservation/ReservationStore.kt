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

package me.ahoo.wow.spi.reservation

import me.ahoo.wow.api.annotation.WowSpi
import kotlin.time.Duration

/**
 * Reserved keys: uniqueness across aggregate instances, such as a user name unique within a tenant
 * (documentation/designs/v10/01-programming-model.md §7, decision D38; 9.x `PrepareKey`).
 *
 * A key in a namespace is held by at most one owner. A decision reserves it **pending** with a short time to live,
 * and confirms it once its events are appended (finding R8): a process that crashes between reserving and appending
 * leaves only a pending reservation that expires, never a key held for good. A confirmed reservation never expires;
 * it ends only when its owner releases it. Moving a key to another owner (a rename) is a saga's work: reserve the new
 * key, then release the old one.
 *
 * Every operation that changes a reservation names its owner and does nothing for anyone else. Expiry is judged by
 * the implementation's clock, so the nodes sharing a store need clocks that agree within a small fraction of the
 * time to live.
 */
@WowSpi
interface ReservationStore {
    /**
     * Reserves [key] in [namespace] for [owner], pending for [ttl]. A key held by no one, or only by an expired
     * pending reservation, is taken. When [owner] already holds the key, a pending reservation's expiry is renewed
     * and a confirmed one stays confirmed.
     *
     * @return the reservation [owner] now holds, or null when another owner's live reservation holds the key.
     */
    suspend fun reserve(namespace: String, key: String, owner: String, ttl: Duration): Reservation?

    /**
     * Confirms [owner]'s reservation of [key], making it permanent. A pending reservation that expired can still be
     * confirmed while no one else has taken the key.
     *
     * @return true when [owner] holds the key confirmed; false when it does not hold the key.
     */
    suspend fun confirm(namespace: String, key: String, owner: String): Boolean

    /**
     * Releases [owner]'s reservation of [key], pending or confirmed.
     *
     * @return true when [owner] held the key and released it.
     */
    suspend fun release(namespace: String, key: String, owner: String): Boolean

    /** The live reservation of [key]: confirmed, or pending and not expired. */
    suspend fun find(namespace: String, key: String): Reservation?
}

/**
 * [owner]'s reservation of [key] in [namespace]. A pending reservation expires at [expiresAt] (epoch milliseconds);
 * a confirmed one has no expiry.
 */
data class Reservation(
    val namespace: String,
    val key: String,
    val owner: String,
    val expiresAt: Long?,
) {
    init {
        require(namespace.isNotBlank()) { "A reservation needs a namespace." }
        require(key.isNotEmpty()) { "A reservation needs a key." }
        require(owner.isNotBlank()) { "A reservation needs an owner." }
    }

    /** Whether the reservation is permanent. */
    val confirmed: Boolean get() = expiresAt == null

    /** Whether the reservation still holds its key at [now] (epoch milliseconds). */
    fun isLive(now: Long): Boolean = expiresAt == null || now < expiresAt
}

/** A reserved [key] in [namespace], without its owner or expiry: what an append records as reserved (decision D50). */
data class ReservationKey(val namespace: String, val key: String) {
    init {
        require(namespace.isNotBlank()) { "A reservation needs a namespace." }
        require(key.isNotEmpty()) { "A reservation needs a key." }
    }
}
