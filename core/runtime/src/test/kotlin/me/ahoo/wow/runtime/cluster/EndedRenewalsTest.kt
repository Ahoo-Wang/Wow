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
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Review r1 (cluster, routing and node): `NodeLoops.kt` says of a background loop that ends with an error (a linkage
 * error of the build, say), "Where the renewals' loop is the one that ended, the partitions' gates close at their
 * unrenewed limit as for any renewal that fails, so the leases lapse and another node takes them over."
 *
 * The gates do close, but the reconcile loop runs on: each reconcile takes the overdue gates as lost
 * ([LeaseRenewals.loseOverdue]) and acquires every partition of its target again ([PartitionRuns.dropLost]), which
 * extends the lease the store still shows as the node's. The node keeps its partitions for good, stopping and starting
 * each one about every `leaseTtl − renewInterval`, and no other node takes them over: the node is still a member, and
 * HRW still gives it its share.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source times the leases.
class EndedRenewalsTest {
    @Test
    fun `a node whose renewals loop ended lets its leases lapse for another node to take`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val broken = BreakingRenewals(store)
            val a = ownership("node-a", broken)
            val b = ownership("node-b", store)
            try {
                a.start()
                b.start()
                delay(10.seconds)
                // Both hold their share.
                a.owned.assert().isNotEmpty()
                b.owned.assert().isNotEmpty()
                (a.owned + b.owned).assert().isEqualTo((0 until PARTITIONS).toSet())

                // A's renewals loop ends: its next round throws an error no round catches.
                broken.broken = true
                val acquiredBefore = broken.acquisitions.get()
                delay(1.minutes)

                // What the loops' KDoc promises: A's leases lapsed, and B took them over.
                val owners = store.owners(GROUP.name).map { it.owner }.toSet()
                (broken.acquisitions.get() - acquiredBefore).assert()
                    .describedAs("acquisitions node-a made after its renewals loop ended")
                    .isZero()
                owners.assert().containsExactly("node-b")
                b.owned.assert().isEqualTo((0 until PARTITIONS).toSet())
            } finally {
                a.abort()
                b.abort()
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

    /** The store's clock follows the test's virtual time. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /** Renewing throws an error once [broken], as a driver of another version than the one built against would. */
    private class BreakingRenewals(private val delegate: LeaseStore) : LeaseStore by delegate {
        @Volatile
        var broken = false

        val acquisitions = AtomicInteger()

        override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? =
            delegate.acquire(group, partition, owner, ttl).also { if (it != null) acquisitions.incrementAndGet() }

        override suspend fun renew(group: String, partitions: Collection<Int>, owner: String, ttl: Duration): List<Lease> {
            if (broken) {
                throw LinkageError("The lease store's driver has no such method.")
            }
            return delegate.renew(group, partitions, owner, ttl)
        }
    }

    private companion object {
        const val PARTITIONS = 8
        val GROUP = LeaseGroup(SERVICE, "renewals")
    }
}
