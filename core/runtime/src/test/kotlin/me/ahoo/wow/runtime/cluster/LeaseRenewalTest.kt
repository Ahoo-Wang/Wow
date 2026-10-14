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
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Spike H6's `RenewalRoundSpike` as a deterministic test, on virtual time: one node holds every partition of a lease
 * store whose calls take a fixed latency, far below the lease TTL, with the default [ClusterOptions]. Renewing one
 * lease after another, a round of 64 renewals at 150 ms took 9.6 s, more than the 7 s margin between the renew
 * interval and the TTL, and the node lost its own leases (89 in 30 s in H6).
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source measures renewal rounds in virtual time.
class LeaseRenewalTest {
    @Test
    fun `a slow store does not cost a healthy node its leases`(): Unit = runTest(timeout = 1.minutes) {
        val store = SlowLeases(InMemoryLeaseStore(virtualClock()), renewLatency = 150.milliseconds)
        val rounds = CopyOnWriteArrayList<RenewalRound>()
        val ownership = ownership(store, rounds)

        try {
            ownership.start()
            delay(30.seconds)

            store.lost.get().assert().isZero()
            ownership.owned.size.assert().isEqualTo(PARTITIONS)
            val margin = ClusterOptions().leaseTtl - ClusterOptions().renewInterval
            val full = rounds.filter { it.leases == PARTITIONS }
            full.assert().isNotEmpty()
            full.forEach {
                it.lost.assert().isZero()
                (it.duration < margin / 2).assert().isTrue()
            }
        } finally {
            ownership.stop()
        }
    }

    @Test
    fun `leases acquired first are renewed while the rest are still being acquired`(): Unit =
        runTest(timeout = 1.minutes) {
            // Acquiring one after another, the last lease came 64 × 300 ms = 19.2 s after the first, and renewing only
            // started then: the first leases had long expired.
            val store = SlowLeases(
                InMemoryLeaseStore(virtualClock()),
                renewLatency = 10.milliseconds,
                acquireLatency = 300.milliseconds,
            )
            val ownership = ownership(store, CopyOnWriteArrayList())

            try {
                ownership.start()
                delay(30.seconds)

                store.lost.get().assert().isZero()
                ownership.owned.size.assert().isEqualTo(PARTITIONS)
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `every renewal round is reported, and a failing renewal does not stop the others`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = SlowLeases(InMemoryLeaseStore(virtualClock()), renewLatency = 10.milliseconds, failing = 3)
            val rounds = CopyOnWriteArrayList<RenewalRound>()
            val ownership = ownership(store, rounds)

            try {
                ownership.start()
                // Short of the TTL: the lease that fails to renew is still live.
                delay(8.seconds)

                val full = rounds.filter { it.leases == PARTITIONS }
                full.assert().isNotEmpty()
                full.forEach {
                    it.group.assert().isEqualTo(GROUP)
                    it.node.assert().isEqualTo(NODE)
                    it.failed.assert().isEqualTo(1)
                    it.lost.assert().isZero()
                }
                ownership.owned.size.assert().isEqualTo(PARTITIONS)
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `a round that outlasts the margin is reported with the leases it lost, even when the hook fails`(): Unit =
        runTest(timeout = 1.minutes) {
            // One renewal at a time: the serial round of H6, 64 × 150 ms = 9.6 s against a 7 s margin.
            val store = SlowLeases(InMemoryLeaseStore(virtualClock()), renewLatency = 150.milliseconds)
            val rounds = CopyOnWriteArrayList<RenewalRound>()
            val ownership = ownership(store, rounds, ClusterOptions(leaseParallelism = 1)) {
                error("The metric hook failed.")
            }
            try {
                ownership.start()
                delay(30.seconds)

                val margin = ClusterOptions().leaseTtl - ClusterOptions().renewInterval
                rounds.any { it.duration > margin }.assert().isTrue()
                rounds.sumOf { it.lost }.assert().isEqualTo(store.lost.get())
                (store.lost.get() > 0).assert().isTrue()
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `at least one lease call runs at a time`() {
        assertThrows<IllegalArgumentException> { ClusterOptions(leaseParallelism = 0) }
    }

    private fun TestScope.ownership(
        store: LeaseStore,
        rounds: MutableList<RenewalRound>,
        options: ClusterOptions = ClusterOptions(),
        afterRound: () -> Unit = {},
    ) = PartitionOwnership(
        GROUP,
        NODE,
        "$NODE.internal:7070",
        PARTITIONS,
        store,
        options,
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
        renewalRounds = {
            rounds += it
            afterRound()
        },
    )

    /** The store's clock follows the test's virtual time. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /**
     * A lease store whose renewals take [renewLatency] and acquisitions [acquireLatency]; it counts the renewals that
     * find their lease lost. Renewing partition [failing] throws.
     */
    private class SlowLeases(
        private val delegate: LeaseStore,
        private val renewLatency: Duration,
        private val acquireLatency: Duration = Duration.ZERO,
        private val failing: Int? = null,
    ) : LeaseStore by delegate {
        val lost = AtomicInteger()

        override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
            delay(acquireLatency)
            return delegate.acquire(group, partition, owner, ttl)
        }

        override suspend fun renew(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
            delay(renewLatency)
            check(partition != failing) { "The store failed to renew $partition." }
            return delegate.renew(group, partition, owner, ttl).also {
                if (it == null) {
                    lost.incrementAndGet()
                }
            }
        }
    }

    private companion object {
        const val PARTITIONS = 64
        const val NODE = "only-node"
        val GROUP = LeaseGroup(SERVICE, "renewal")
    }
}
