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
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import java.time.Clock
import kotlin.time.Duration

/**
 * A [LeaseStore] in memory, for tests and single-process samples; its time comes from [clock]. One lock serializes
 * every operation. A released lease is kept with expiry 0, so the next acquisition continues its epoch.
 */
@InternalWowApi
class InMemoryLeaseStore(private val clock: Clock = Clock.systemUTC()) : LeaseStore {
    private val lock = Mutex()
    private val leases = HashMap<Pair<String, Int>, Lease>()
    private val members = HashMap<Pair<String, String>, Member>()

    override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? = lock.withLock {
        val now = clock.millis()
        val current = leases[group to partition]
        val live = current != null && current.expiresAt > now
        if (live && current.owner != owner) {
            return null
        }
        val epoch = when {
            current == null -> 1L
            live -> current.epoch
            else -> current.epoch + 1
        }
        Lease(group, partition, owner, epoch, now + ttl.inWholeMilliseconds).also { leases[group to partition] = it }
    }

    override suspend fun renew(group: String, partition: Int, owner: String, ttl: Duration): Lease? = lock.withLock {
        val now = clock.millis()
        val current = held(group, partition, owner, now) ?: return null
        current.copy(expiresAt = now + ttl.inWholeMilliseconds).also { leases[group to partition] = it }
    }

    override suspend fun release(group: String, partition: Int, owner: String): Boolean = lock.withLock {
        val current = held(group, partition, owner, clock.millis()) ?: return false
        leases[group to partition] = current.copy(expiresAt = 0)
        true
    }

    override suspend fun owners(group: String): List<Lease> = lock.withLock {
        val now = clock.millis()
        leases.values.filter { it.group == group && it.expiresAt > now }.sortedBy { it.partition }
    }

    override suspend fun heartbeat(group: String, node: String, address: String, ttl: Duration): Member =
        lock.withLock {
            Member(group, node, address, clock.millis() + ttl.inWholeMilliseconds).also { members[group to node] = it }
        }

    override suspend fun members(group: String): List<Member> = lock.withLock {
        val now = clock.millis()
        members.values.filter { it.group == group && it.expiresAt > now }.sortedBy { it.node }
    }

    override suspend fun leave(group: String, node: String) {
        lock.withLock { members.remove(group to node) }
    }

    private fun held(group: String, partition: Int, owner: String, now: Long): Lease? =
        leases[group to partition]?.takeIf { it.owner == owner && it.expiresAt > now }
}
