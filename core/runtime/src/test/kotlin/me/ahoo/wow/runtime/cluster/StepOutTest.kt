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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
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
 * The step-out of a node that met a fatal error (X-80, [PartitionOwnership.stepOut]): for good, bounded, and whatever
 * the hand-off comes to, renewing no lease, so the other node takes every partition, on expiry at worst. A group whose
 * lease renewals ended steps out the same way ([LeaseRenewals.launchIn]), and so does every group of a membership whose
 * heartbeats or reads of the members ended ([ServiceMembership.whenEnded]).
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source times the leases.
class StepOutTest {
    @Test
    fun `a step-out hands every partition to the other node, for good, and twice does nothing`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val fatal = ownership(FATAL, store)
            val other = ownership(OTHER, store)
            try {
                fatal.start()
                other.start()
                delay(5.seconds)
                fatal.owned.assert().isNotEmpty()

                fatal.stepOut(BOUND)
                fatal.stepOut(BOUND)
                delay(5.seconds)
                other.owned.assert().isEqualTo((0 until PARTITIONS).toSet())
                fatal.owned.assert().isEmpty()
                members(store).assert().containsExactly(OTHER)
                // It reconciles no more: nothing is acquired back.
                fatal.reconcile()
                fatal.owned.assert().isEmpty()
            } finally {
                other.stop()
                fatal.stop()
            }
        }

    @Test
    fun `an ownership that stepped out before it started does not start`(): Unit = runTest(timeout = 1.minutes) {
        val ownership = ownership(FATAL, InMemoryLeaseStore(virtualClock()))
        try {
            ownership.stepOut(BOUND)
            runCatching { ownership.start() }.exceptionOrNull()?.message.assert().contains("stepped out")
            ownership.isRunning.assert().isFalse()
        } finally {
            // Nothing once a stop has run; ends the ownership's loops on the test's scheduler when it did not.
            ownership.abort()
        }
    }

    @Test
    fun `a reconcile of an ownership that does not run acquires nothing`(): Unit = runTest(timeout = 1.minutes) {
        val store = InMemoryLeaseStore(virtualClock())
        val ownership = ownership(FATAL, store)
        try {
            // Alone in its view of the members, it would take every partition.
            ownership.reconcile()
            ownership.owned.assert().isEmpty()
            store.owners(GROUP.name).assert().isEmpty()
        } finally {
            ownership.abort()
        }
    }

    @Test
    fun `a hand-off that outlasts its bound renews no lease, so the other node takes them once they expire`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            // No release of the fatal node answers: its hand-off cannot end.
            val hanging = HangingReleases(store)
            val fatal = ownership(FATAL, hanging)
            val other = ownership(OTHER, store)
            try {
                fatal.start()
                other.start()
                delay(5.seconds)
                val held = fatal.owned
                held.assert().isNotEmpty()

                hanging.hangs = true
                fatal.stepOut(BOUND)
                // Past the bound and the leases' time to live: the other node owns them, the fatal one renewed none.
                delay(1.minutes)
                other.owned.assert().isEqualTo((0 until PARTITIONS).toSet())
                fatal.owned.assert().isEmpty()
            } finally {
                hanging.hangs = false
                hanging.release.complete(Unit)
                other.stop()
                fatal.stop()
            }
        }

    @Test
    fun `a step-out goes on without a lifecycle a stuck start holds, and that start backs out`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            // The first heartbeat of the fatal node's join does not answer until released.
            val joining = HangingHeartbeat(store)
            val fatal = ownership(FATAL, joining)
            try {
                val starting = async { runCatching { fatal.start() } }
                delay(1.seconds)

                fatal.stepOut(BOUND)
                joining.release.complete(Unit)
                starting.await().exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
                fatal.isRunning.assert().isFalse()
                members(store).assert().isEmpty()
            } finally {
                // A start still waiting on the heartbeat holds the lifecycle: released first, so the abort can take it.
                joining.release.complete(Unit)
                // Nothing once a stop has run; ends the ownership's loops on the test's scheduler when it did not.
                fatal.abort()
            }
        }

    @Test
    fun `a node whose renewals ended steps out, acquires none again, and the other node takes its partitions`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val breaking = BreakingRenewals(store)
            val ended = ownership(FATAL, breaking)
            val other = ownership(OTHER, store)
            try {
                ended.start()
                other.start()
                delay(10.seconds)
                ended.owned.assert().isNotEmpty()
                (ended.owned + other.owned).assert().isEqualTo((0 until PARTITIONS).toSet())

                // Its next round throws an error no round catches: the loop ends, and nothing restarts it.
                breaking.broken = true
                val acquired = breaking.acquisitions.get()
                delay(1.minutes)

                (breaking.acquisitions.get() - acquired).assert()
                    .describedAs("acquisitions of the node whose renewals loop ended")
                    .isZero()
                ended.isSteppedOut.assert().isTrue()
                ended.owned.assert().isEmpty()
                members(store).assert().containsExactly(OTHER)
                store.owners(GROUP.name).map { it.owner }.toSet().assert().containsExactly(OTHER)
                other.owned.assert().isEqualTo((0 until PARTITIONS).toSet())
            } finally {
                other.stop()
                ended.stop()
            }
        }

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
            owners(store).assert().containsExactly("node-b")
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
                owners(store).assert().doesNotContain("node-a")
                c.owned.assert().isEqualTo(ALL)
                a.owned.assert().isEmpty()
            } finally {
                a.abort()
                b.abort()
                c.abort()
            }
        }

    @Test
    fun `every group of a membership whose heartbeats ended steps out, and the membership joins none again`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val breaking = BreakingMembership(store)
            val membership = ServiceMembership(
                GROUP.service,
                "node-a",
                { "node-a.internal:7070" },
                breaking,
                context = StandardTestDispatcher(testScheduler),
                timeSource = testScheduler.timeSource,
            )
            val orders = ownership("node-a", breaking, GROUP, membership)
            val payments = ownership("node-a", breaking, PAYMENTS, membership)
            try {
                orders.start()
                payments.start()
                delay(10.seconds)
                payments.owned.assert().isEqualTo(ALL)

                breaking.heartbeats = true
                delay(10.seconds)

                // Both groups the membership serves stepped out, and handed their partitions off.
                listOf(orders, payments).forEach {
                    it.isSteppedOut.assert().isTrue()
                    it.owned.assert().isEmpty()
                }
                (store.owners(GROUP.name) + store.owners(PAYMENTS.name)).assert().isEmpty()
                runCatching { membership.join("${GROUP.service}/audit") {} }.exceptionOrNull()?.message.assert()
                    .contains("takes part in ${GROUP.service} no more: its membership heartbeats ended")
            } finally {
                orders.abort()
                payments.abort()
            }
        }

    private fun TestScope.ownership(
        node: String,
        leases: LeaseStore,
        group: LeaseGroup = GROUP,
        membership: ServiceMembership? = null,
    ) = PartitionOwnership(
        group,
        node,
        { "$node.internal:7070" },
        PARTITIONS,
        leases,
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
        membership = membership,
    )

    private suspend fun owners(store: LeaseStore): Set<String> = store.owners(GROUP.name).mapTo(HashSet()) { it.owner }

    private suspend fun members(store: LeaseStore): List<String> =
        store.members(GROUP.service).filter { GROUP.name in it.groups }.map { it.node }

    /** The store's clock follows the test's virtual time. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /** A release that waits for [release] while [hangs]. */
    private class HangingReleases(private val delegate: LeaseStore) : LeaseStore by delegate {
        @Volatile
        var hangs = false
        val release = CompletableDeferred<Unit>()

        override suspend fun release(group: String, partition: Int, owner: String): Boolean {
            if (hangs) release.await()
            return delegate.release(group, partition, owner)
        }
    }

    /** A renewal throws an error once [broken], as a driver of another version than the one built against would. */
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

    /** Once set, a heartbeat or a read of the members throws, as a driver built against another version would. */
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

    /** The first heartbeat waits for [release]; the others answer at once. */
    private class HangingHeartbeat(private val delegate: LeaseStore) : LeaseStore by delegate {
        val release = CompletableDeferred<Unit>()

        override suspend fun heartbeat(
            service: String,
            node: String,
            address: String,
            ttl: Duration,
            groups: Set<String>,
        ): Member {
            release.await()
            return delegate.heartbeat(service, node, address, ttl, groups)
        }
    }

    private companion object {
        val GROUP = LeaseGroup("svc", "orders")
        val PAYMENTS = LeaseGroup("svc", "payments")
        const val PARTITIONS = 8
        const val FATAL = "fatal"
        const val OTHER = "other"
        val BOUND = 10.seconds
        val ALL = (0 until PARTITIONS).toSet()
    }
}
