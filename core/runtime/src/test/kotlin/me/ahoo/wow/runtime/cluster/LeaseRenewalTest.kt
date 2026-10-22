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
    fun `every renewal round renews all its leases with one call and is reported`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = SlowLeases(InMemoryLeaseStore(virtualClock()), renewLatency = 10.milliseconds)
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
                    it.failed.assert().isZero()
                    it.lost.assert().isZero()
                }
                // Decision D270: one store call a round, whatever the leases held.
                store.calls.get().assert().isEqualTo(rounds.count { it.leases > 0 })
                ownership.owned.size.assert().isEqualTo(PARTITIONS)
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `a round that outlasts the margin is reported with the leases it lost, even when the hook fails`(): Unit =
        runTest(timeout = 1.minutes) {
            // A store that takes 8 s to renew, against a 7 s margin (spike H6).
            val store = SlowLeases(InMemoryLeaseStore(virtualClock()), renewLatency = 8.seconds)
            val rounds = CopyOnWriteArrayList<RenewalRound>()
            val ownership = ownership(store, rounds) {
                error("The metric hook failed.")
            }
            try {
                ownership.start()
                delay(30.seconds)

                val margin = ClusterOptions().leaseTtl - ClusterOptions().renewInterval
                rounds.any { it.duration > margin }.assert().isTrue()
                // Lost in the store, or taken as lost here first, unrenewed for the margin (finding A1-8).
                (rounds.sumOf { it.lost } >= store.lost.get()).assert().isTrue()
                (rounds.sumOf { it.lost } > 0).assert().isTrue()
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `a lease the store cannot renew stops the partition before it may expire, and comes back after`(): Unit =
        runTest(timeout = 1.minutes) {
            // Finding A1-8: the lease store is unreachable, so every renewal throws, while the node runs on.
            val delegate = InMemoryLeaseStore(virtualClock())
            var down = false
            val store = object : LeaseStore by delegate {
                override suspend fun renew(
                    group: String,
                    partitions: Collection<Int>,
                    owner: String,
                    ttl: Duration
                ): List<Lease> {
                    check(!down) { "The lease store is unreachable." }
                    return delegate.renew(group, partitions, owner, ttl)
                }

                override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
                    check(!down) { "The lease store is unreachable." }
                    return delegate.acquire(group, partition, owner, ttl)
                }
            }
            val rounds = CopyOnWriteArrayList<RenewalRound>()
            val ownership = ownership(store, rounds)
            val limit = ClusterOptions().leaseTtl - ClusterOptions().renewInterval
            try {
                ownership.start()
                delay(1.seconds)
                ownership.owned.size.assert().isEqualTo(PARTITIONS)
                down = true
                // Within the margin the partitions still run: a lease renewed a moment ago is live.
                delay(limit - 2.seconds)
                ownership.owned.size.assert().isEqualTo(PARTITIONS)
                ownership.runIfAccepting(0) { "ran" }.assert().isEqualTo("ran")
                delay(3.seconds)
                ownership.owned.assert().isEmpty()
                ownership.accepts(0).assert().isFalse()
                ownership.runIfAccepting(0) { "ran" }.assert().isNull()
                rounds.filter { it.failed == PARTITIONS }.assert().isNotEmpty()
                // Not released: the store still names this node until the leases expire.
                delegate.owners(GROUP.name).map { it.owner }.toSet().assert().containsExactly(NODE)

                down = false
                delay(5.seconds)
                ownership.owned.size.assert().isEqualTo(PARTITIONS)
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `at least one lease call runs at a time`() {
        assertThrows<IllegalArgumentException> { ClusterOptions(leaseParallelism = 0) }
    }

    @Test
    fun `a lease is renewed more than twice within its TTL`() {
        // Review R2.1-5: one unrenewed for leaseTtl - renewInterval is taken as lost, before the next round.
        assertThrows<IllegalArgumentException> { ClusterOptions(leaseTtl = 10.seconds, renewInterval = 6.seconds) }
            .message.assert().contains("less than half the lease TTL")
        assertThrows<IllegalArgumentException> { ClusterOptions(leaseTtl = 10.seconds, renewInterval = 5.seconds) }
        ClusterOptions(leaseTtl = 10.seconds, renewInterval = 4_999.milliseconds).renewInterval
            .assert().isEqualTo(4_999.milliseconds)
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
    ) : LeaseStore by delegate {
        val lost = AtomicInteger()
        val calls = AtomicInteger()

        override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
            delay(acquireLatency)
            return delegate.acquire(group, partition, owner, ttl)
        }

        /** One call a round (decision D270): [renewLatency] once for every partition, as one statement takes. */
        override suspend fun renew(
            group: String,
            partitions: Collection<Int>,
            owner: String,
            ttl: Duration
        ): List<Lease> {
            calls.incrementAndGet()
            delay(renewLatency)
            return delegate.renew(group, partitions, owner, ttl).also { lost.addAndGet(partitions.size - it.size) }
        }
    }

    private companion object {
        const val PARTITIONS = 64
        const val NODE = "only-node"
        val GROUP = LeaseGroup(SERVICE, "renewal")
    }
}
