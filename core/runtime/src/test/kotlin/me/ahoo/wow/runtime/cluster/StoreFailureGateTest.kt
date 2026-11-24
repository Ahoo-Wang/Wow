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
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
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
 * #6675 on virtual time: while the lease store fails, a node's lease groups on it ask no acquisition per missing
 * partition and round, each holding a connection until it times out, but one at a time for all of them after a growing
 * window; a takeover, whose acquisitions find partitions another node still holds, is never held back; and once the
 * store answers again, the missing partitions are acquired at once.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source times the leases, the windows and the hangs.
class StoreFailureGateTest {
    private val options = ClusterOptions()

    @Test
    fun `hanging acquisitions are asked one at a time for every group of the store, never one per partition`(): Unit =
        runTest(timeout = 2.minutes) {
            val leases = DegradedLeases(InMemoryLeaseStore(virtualClock()), hang = OPERATION_TIMEOUT)
            val membership = membership(leases)
            val shop = ownership(SHOP, leases, membership)
            val other = ownership(OTHER, leases, membership)
            try {
                shop.start()
                other.start()
                shop.owned.size.assert().isEqualTo(PARTITIONS)
                other.owned.size.assert().isEqualTo(PARTITIONS)

                leases.down = true
                leases.acquisitions.mostInFlight.set(0)
                delay(2.minutes)

                // Leases unrenewed past the margin opened the gate before any acquisition was asked: no first wave
                // of one acquisition per partition and group, which a hanging store would hold for the whole timeout.
                leases.acquisitions.mostInFlight.get().assert().isEqualTo(1)
                membership.acquisitions.open.assert().isTrue()
            } finally {
                leases.down = false
                shop.stop()
                other.stop()
            }
        }

    @Test
    fun `failing acquisitions are asked a few times a window, not once per missing partition and round`(): Unit =
        runTest(timeout = 2.minutes) {
            val leases = DegradedLeases(InMemoryLeaseStore(virtualClock()), hang = Duration.ZERO)
            val membership = membership(leases)
            val shop = ownership(SHOP, leases, membership)
            val other = ownership(OTHER, leases, membership)
            try {
                shop.start()
                other.start()
                leases.down = true
                leases.acquisitions.calls.set(0)
                delay(1.minutes)
                // One per round and partition would be 60 rounds × 2 × 16; the windows reach the TTL (10 s) after a
                // few failures, so a minute asks a handful.
                leases.acquisitions.calls.get().assert().isLessThan(20)
            } finally {
                leases.down = false
                shop.stop()
                other.stop()
            }
        }

    @Test
    fun `a takeover whose acquisitions find another node's leases is never held back`(): Unit = runTest {
        val store = InMemoryLeaseStore(virtualClock())
        val first = ownership(SHOP, store, membership(store, "node-1"), "node-1")
        first.start()
        first.owned.size.assert().isEqualTo(PARTITIONS)
        first.abort()

        val leases = DegradedLeases(store, hang = Duration.ZERO)
        val membership = membership(leases, "node-2")
        val second = ownership(SHOP, leases, membership, "node-2")
        try {
            val crashed = testScheduler.currentTime
            second.start()
            while (second.owned.size < PARTITIONS) {
                // Every round asks the partitions node-1 still holds: the store answers, so nothing is held back.
                membership.acquisitions.open.assert().isFalse()
                delay(options.reconcileInterval)
            }
            (testScheduler.currentTime - crashed).assert()
                .isLessThanOrEqualTo((options.leaseTtl + options.reconcileInterval * 2).inWholeMilliseconds)
        } finally {
            second.stop()
        }
    }

    @Test
    fun `once the store answers again every missing partition is acquired within a window`(): Unit =
        runTest(timeout = 2.minutes) {
            val leases = DegradedLeases(InMemoryLeaseStore(virtualClock()), hang = OPERATION_TIMEOUT)
            val membership = membership(leases)
            val shop = ownership(SHOP, leases, membership)
            try {
                shop.start()
                leases.down = true
                delay(1.minutes)
                shop.owned.assert().isEmpty()

                leases.down = false
                val healed = testScheduler.currentTime
                while (shop.owned.size < PARTITIONS) {
                    delay(options.reconcileInterval)
                }
                // A window at most (the TTL), a probe still out at most (the timeout), then one round.
                (testScheduler.currentTime - healed).assert().isLessThanOrEqualTo(
                    (options.leaseTtl + OPERATION_TIMEOUT + options.reconcileInterval * 2).inWholeMilliseconds
                )
                membership.acquisitions.open.assert().isFalse()
            } finally {
                shop.stop()
            }
        }

    private fun TestScope.membership(leases: LeaseStore, node: String = NODE) = ServiceMembership(
        SERVICE_NAME,
        node,
        { "$node.internal:7070" },
        leases,
        options,
        StandardTestDispatcher(testScheduler),
        testScheduler.timeSource,
    )

    private fun TestScope.ownership(
        group: LeaseGroup,
        leases: LeaseStore,
        membership: ServiceMembership,
        node: String = NODE,
    ) = PartitionOwnership(
        group,
        node,
        { "$node.internal:7070" },
        PARTITIONS,
        leases,
        options,
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
        membership = membership,
    )

    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /** The acquisitions a [DegradedLeases] took: how many, and the most outstanding at once. */
    class Calls {
        val calls = AtomicInteger()
        val mostInFlight = AtomicInteger()
        private val inFlight = AtomicInteger()

        suspend fun <T> count(call: suspend () -> T): T {
            calls.incrementAndGet()
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                return call()
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /**
     * A lease store whose writes, while [down], wait [hang] (a store that cannot acknowledge them) and then fail: its
     * acquisitions, renewals and heartbeats. Its reads always answer, as a replica set's primary still does without a
     * majority.
     */
    class DegradedLeases(
        private val delegate: LeaseStore,
        private val hang: Duration,
    ) : LeaseStore by delegate {
        @Volatile
        var down = false

        val acquisitions = Calls()

        private suspend fun <T> write(call: suspend () -> T): T {
            if (down) {
                delay(hang)
                throw StorageUnavailableException("The lease store cannot acknowledge a write.")
            }
            return call()
        }

        override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? =
            acquisitions.count { write { delegate.acquire(group, partition, owner, ttl) } }

        override suspend fun renew(group: String, partitions: Collection<Int>, owner: String, ttl: Duration) =
            write { delegate.renew(group, partitions, owner, ttl) }

        override suspend fun heartbeat(
            service: String,
            node: String,
            address: String,
            ttl: Duration,
            groups: Set<String>,
        ): Member = write { delegate.heartbeat(service, node, address, ttl, groups) }
    }

    private companion object {
        const val PARTITIONS = 16
        const val NODE = "node-1"
        const val SERVICE_NAME = "gate-service"
        val SHOP = LeaseGroup(SERVICE_NAME, "shop")
        val OTHER = LeaseGroup(SERVICE_NAME, "other")

        /** How long the store holds a write it cannot acknowledge: MongoDB's default operation timeout. */
        val OPERATION_TIMEOUT = 30.seconds
    }
}
