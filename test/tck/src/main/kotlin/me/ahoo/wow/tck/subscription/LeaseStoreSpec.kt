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

package me.ahoo.wow.tck.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import me.ahoo.wow.tck.MutableClock
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The [LeaseStore] contract (documentation/designs/04-subscriptions.md §1, decisions D24 and D45) every backend
 * passes. A backend's test extends it and supplies a store whose time comes from [clock]; each test uses its own
 * group, so the tests may share one table.
 */
abstract class LeaseStoreSpec {
    protected abstract fun createStore(clock: Clock): LeaseStore

    private val group = "tck_" + UUID.randomUUID().toString().replace("-", "").take(GROUP_SUFFIX)
    private val clock = MutableClock()
    private val store by lazy { createStore(clock) }

    @Test
    fun `a free partition is acquired at epoch 1 until now plus the ttl`(): Unit = runBlocking {
        val lease = store.acquire(group, 0, "node-a", TTL)
        lease.assert().isEqualTo(Lease(group, 0, "node-a", 1, clock.millis() + TTL.inWholeMilliseconds))
        store.owners(group).assert().containsExactly(lease)
    }

    @Test
    fun `concurrent acquisitions of a partition have exactly one winner`(): Unit = runBlocking {
        val leases = coroutineScope {
            (1..CONTENDERS).map { node -> async(Dispatchers.Default) { store.acquire(group, 0, "node-$node", TTL) } }
                .awaitAll()
        }
        val winner = leases.filterNotNull().single()
        winner.epoch.assert().isEqualTo(1L)
        store.owners(group).assert().containsExactly(winner)
    }

    @Test
    fun `concurrent acquisitions of an expired partition have exactly one winner`(): Unit = runBlocking {
        store.acquire(group, 0, "node-0", TTL)
        clock.advance(TTL)
        val leases = coroutineScope {
            (1..CONTENDERS).map { node -> async(Dispatchers.Default) { store.acquire(group, 0, "node-$node", TTL) } }
                .awaitAll()
        }
        val winner = leases.filterNotNull().single()
        winner.epoch.assert().isEqualTo(2L)
        store.owners(group).assert().containsExactly(winner)
    }

    @Test
    fun `a live lease cannot be taken, an expired one can`(): Unit = runBlocking {
        store.acquire(group, 0, "node-a", TTL)
        clock.advance(TTL - 1.seconds)
        store.acquire(group, 0, "node-b", TTL).assert().isNull()
        clock.advance(1.seconds)
        store.owners(group).assert().isEmpty()
        val taken = store.acquire(group, 0, "node-b", TTL)
        taken.assert().isEqualTo(Lease(group, 0, "node-b", 2, clock.millis() + TTL.inWholeMilliseconds))
        store.renew(group, listOf(0), "node-a", TTL).assert().isEmpty()
        store.release(group, 0, "node-a").assert().isFalse()
        store.owners(group).assert().containsExactly(taken)
    }

    @Test
    fun `the owner acquiring again extends its lease and keeps the epoch`(): Unit = runBlocking {
        store.acquire(group, 0, "node-a", TTL)
        clock.advance(TTL - 1.seconds)
        store.acquire(group, 0, "node-a", TTL)
            .assert().isEqualTo(Lease(group, 0, "node-a", 1, clock.millis() + TTL.inWholeMilliseconds))
    }

    @Test
    fun `an owner taking back its expired lease starts a new epoch`(): Unit = runBlocking {
        store.acquire(group, 0, "node-a", TTL)
        clock.advance(TTL)
        store.acquire(group, 0, "node-a", TTL)?.epoch.assert().isEqualTo(2L)
    }

    @Test
    fun `only the owner renews, and only while the lease is live`(): Unit = runBlocking {
        store.acquire(group, 0, "node-a", TTL)
        clock.advance(TTL - 1.seconds)
        store.renew(group, listOf(0), "node-b", TTL).assert().isEmpty()
        val renewed = store.renew(group, listOf(0), "node-a", TTL)
        renewed.assert().containsExactly(Lease(group, 0, "node-a", 1, clock.millis() + TTL.inWholeMilliseconds))
        clock.advance(TTL - 1.seconds)
        store.owners(group).assert().isEqualTo(renewed)
        clock.advance(1.seconds)
        store.renew(group, listOf(0), "node-a", TTL).assert().isEmpty()
        store.renew(group, listOf(1), "node-a", TTL).assert().isEmpty()
    }

    @Test
    fun `a renewal renews the live leases the owner holds and leaves the others out`(): Unit = runBlocking {
        store.acquire(group, 0, "node-a", TTL)
        store.acquire(group, 1, "node-a", TTL)
        store.acquire(group, 2, "node-b", TTL)
        store.acquire(group, 3, "node-a", TTL)
        store.release(group, 3, "node-a")
        clock.advance(TTL - 1.seconds)
        val expiresAt = clock.millis() + TTL.inWholeMilliseconds
        store.renew(group, listOf(0, 1, 2, 3, 4), "node-a", TTL).sortedBy { it.partition }.assert().containsExactly(
            Lease(group, 0, "node-a", 1, expiresAt),
            Lease(group, 1, "node-a", 1, expiresAt),
        )
        store.owners(
            group
        ).filter { it.owner == "node-a" }.map { it.expiresAt }.assert().containsExactly(expiresAt, expiresAt)
        clock.advance(TTL)
        store.renew(group, listOf(0, 1), "node-a", TTL).assert().isEmpty()
        store.renew(group, emptyList(), "node-a", TTL).assert().isEmpty()
    }

    @Test
    fun `a released lease can be acquired at once`(): Unit = runBlocking {
        store.acquire(group, 0, "node-a", TTL)
        store.release(group, 0, "node-b").assert().isFalse()
        store.acquire(group, 0, "node-b", TTL).assert().isNull()
        store.release(group, 0, "node-a").assert().isTrue()
        store.release(group, 0, "node-a").assert().isFalse()
        store.owners(group).assert().isEmpty()
        store.renew(group, listOf(0), "node-a", TTL).assert().isEmpty()
        store.acquire(group, 0, "node-b", TTL)?.epoch.assert().isEqualTo(2L)
        store.release(group, 1, "node-a").assert().isFalse()
    }

    @Test
    fun `owners lists the live leases of one group in partition order`(): Unit = runBlocking {
        val other = group + "_other"
        store.acquire(group, 2, "node-a", TTL * 2)
        store.acquire(group, 0, "node-b", TTL)
        store.acquire(group, 1, "node-a", TTL)
        store.acquire(other, 0, "node-c", TTL)
        store.owners(group).map { it.partition to it.owner }
            .assert().containsExactly(0 to "node-b", 1 to "node-a", 2 to "node-a")
        clock.advance(TTL)
        store.owners(group).map { it.partition to it.owner }.assert().containsExactly(2 to "node-a")
        store.owners(other).assert().isEmpty()
    }

    @Test
    fun `members are the nodes whose heartbeat is live`(): Unit = runBlocking {
        val other = group + "_other"
        store.heartbeat(group, "node-b", address("node-b"), TTL)
            .assert().isEqualTo(Member(group, "node-b", address("node-b"), clock.millis() + TTL.inWholeMilliseconds))
        store.heartbeat(group, "node-a", address("node-a"), TTL)
        store.heartbeat(other, "node-c", address("node-c"), TTL)
        store.members(group).map { it.node }.assert().containsExactly("node-a", "node-b")
        clock.advance(TTL - 1.seconds)
        store.heartbeat(group, "node-a", address("node-a"), TTL)
        clock.advance(1.seconds)
        store.members(group).map { it.node }.assert().containsExactly("node-a")
        store.heartbeat(group, "node-b", address("node-b"), TTL)
        store.members(group).map { it.node }.assert().containsExactly("node-a", "node-b")
    }

    @Test
    fun `a node that leaves is no longer a member but keeps its leases`(): Unit = runBlocking {
        store.heartbeat(group, "node-a", address("node-a"), TTL)
        store.heartbeat(group, "node-b", address("node-b"), TTL)
        val lease = store.acquire(group, 0, "node-a", TTL)
        store.leave(group, "node-a")
        store.leave(group, "node-missing")
        store.members(group).map { it.node }.assert().containsExactly("node-b")
        store.owners(group).assert().containsExactly(lease)
    }

    @Test
    fun `members carry the address of their last heartbeat`(): Unit = runBlocking {
        store.heartbeat(group, "node-a", "10.0.0.1:7070", TTL)
        store.heartbeat(group, "node-b", "10.0.0.2:7070", TTL)
        store.members(group).map { it.node to it.address }
            .assert().containsExactly("node-a" to "10.0.0.1:7070", "node-b" to "10.0.0.2:7070")
        clock.advance(1.seconds)
        val moved = store.heartbeat(group, "node-a", "10.0.0.9:7071", TTL)
        moved.assert().isEqualTo(Member(group, "node-a", "10.0.0.9:7071", clock.millis() + TTL.inWholeMilliseconds))
        store.members(group).first().assert().isEqualTo(moved)
    }

    protected companion object {
        const val GROUP_SUFFIX = 12
        const val CONTENDERS = 16
        val TTL = 10.seconds

        /** The internal address the tests give [node]. */
        fun address(node: String): String = "$node.internal:7070"
    }
}
