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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Review r1 follow-up (cluster): the two loops of [ServiceMembership], the heartbeat and the read of the members, end
 * on an error no round catches (`NodeLoops.kt`), and nothing restarts them. The node's lease renewals run on and paper
 * over the end: the node keeps renewing the share it last computed, while the other nodes see it gone (no heartbeat)
 * or it does not see them (no read).
 *
 * What these tests hold is what #6741 settled for the renewals' loop: a node that can no longer take part in the
 * membership steps out of its groups, so the others take its partitions over.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source times the leases.
class EndedMembershipLoopsTest {
    @Test
    fun `a node whose heartbeats ended lets the other node take its partitions`(): Unit = runTest(timeout = 1.minutes) {
        val store = InMemoryLeaseStore(virtualClock())
        val breaking = BreakingMembership(store)
        val a = ownership("node-a", breaking)
        val b = ownership("node-b", store)
        try {
            a.start()
            b.start()
            delay(10.seconds)
            a.owned.assert().isNotEmpty()

            // A's next heartbeat throws an error no round catches: the loop ends, and A's record expires.
            breaking.heartbeats = true
            delay(1.minutes)

            // B no longer counts A, so its HRW gives B every partition; A holds none of their leases.
            owners(store).assert().containsExactly("node-b") // fails: A renews its share for good
            b.owned.assert().isEqualTo(ALL)
            a.owned.assert().isEmpty()
        } finally {
            a.abort()
            b.abort()
        }
    }

    @Test
    fun `a node whose reads of the members ended lets the others take the share of a node that left`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val breaking = BreakingMembership(store)
            val a = ownership("node-a", breaking)
            val b = ownership("node-b", store)
            val c = ownership("node-c", store)
            try {
                a.start()
                b.start()
                c.start()
                delay(10.seconds)

                // A's next read of the members throws: the loop ends, and A's view stays {A, B, C}.
                breaking.reads = true
                delay(10.seconds)
                b.stop()
                delay(1.minutes)

                // A computes its share on a view that no longer is the group's: it holds none of the leases, and C, the
                // one member left, owns every partition, B's share included.
                owners(store).assert().doesNotContain("node-a") // fails: A renews its stale share
                c.owned.assert().isEqualTo(ALL)
                a.owned.assert().isEmpty()
            } finally {
                a.abort()
                b.abort()
                c.abort()
            }
        }

    private fun TestScope.ownership(node: String, leases: LeaseStore) = PartitionOwnership(
        GROUP,
        node,
        "$node.internal:7070",
        PARTITIONS,
        leases,
        ClusterOptions(),
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
    )

    private suspend fun owners(store: LeaseStore): Set<String> = store.owners(GROUP.name).mapTo(HashSet()) { it.owner }

    /** The store's clock follows the test's virtual time. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /** Once set, a heartbeat or a read of the members throws, as a driver of another version than the one built against would. */
    private class BreakingMembership(private val delegate: LeaseStore) : LeaseStore by delegate {
        @Volatile
        var heartbeats = false

        @Volatile
        var reads = false

        override suspend fun heartbeat(
            service: String,
            node: String,
            address: String,
            ttl: Duration,
            groups: Set<String>,
        ): Member {
            if (heartbeats) {
                throw LinkageError("The lease store's driver has no such method.")
            }
            return delegate.heartbeat(service, node, address, ttl, groups)
        }

        override suspend fun members(service: String): List<Member> {
            if (reads) {
                throw LinkageError("The lease store's driver has no such method.")
            }
            return delegate.members(service)
        }
    }

    private companion object {
        const val PARTITIONS = 16
        val ALL = (0 until PARTITIONS).toSet()
        val GROUP = LeaseGroup(SERVICE, "membership")
    }
}
