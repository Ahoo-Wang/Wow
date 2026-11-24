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

package me.ahoo.wow.spi.subscription

import me.ahoo.wow.api.annotation.InternalWowApi
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * When a store's heartbeat deletes the member records it finds expired for [LeaseStore.EXPIRED_MEMBER_TTLS] TTLs
 * (#6703): at most once per TTL for each key the store heartbeats, the time read from [clock]. It keeps, in memory, the
 * time of the last delete of each key: one entry per key the store instance heartbeats, a handful, dropped with the
 * store. A store opened again (a node restarted) deletes once at its first heartbeat of each key.
 */
@InternalWowApi
class ExpiredMembers(private val clock: Clock) {
    private val deleted = ConcurrentHashMap<String, Long>()

    /**
     * The expiry before which the records of [service] are to be deleted now, by a heartbeat of [ttl]; null when they
     * were deleted less than [ttl] ago. A clock moved back holds the next delete until it passes the last one's time
     * plus [ttl] again.
     */
    fun due(service: String, ttl: Duration): Long? {
        val now = clock.millis()
        val ttlMillis = ttl.inWholeMilliseconds
        var due = false
        deleted.compute(service) { _, last ->
            if (last == null || now - last >= ttlMillis) {
                due = true
                now
            } else {
                last
            }
        }
        return if (due) now - LeaseStore.EXPIRED_MEMBER_TTLS * ttlMillis else null
    }

    /**
     * How long a heartbeat of [ttl] lets its delete take at most, besides what is left of the heartbeat's bound: a
     * quarter of [ttl], so a delete that hangs never holds the heartbeat past its interval (less than half the
     * TTL), and the node's own record, written first, is written again before it expires.
     */
    fun deleteBound(ttl: Duration): Duration = ttl / SHARE_OF_TTL

    private companion object {
        const val SHARE_OF_TTL = 4
    }
}
