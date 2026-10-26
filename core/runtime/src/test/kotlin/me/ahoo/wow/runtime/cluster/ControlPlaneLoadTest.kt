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
import me.ahoo.wow.spi.subscription.Member
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The store operations of the control plane in steady state (finding R5.B-4), counted on virtual time for 15 lease
 * groups on 10 nodes with the default [ClusterOptions] (heartbeat and renewal every 3 s, reconcile and route refresh
 * every 1 s).
 *
 * Before, each group kept its own membership: per group and node a heartbeat every 3 s, a read of the members for each
 * reconcile (1 s) and another for each route refresh (1 s), a renewal every 3 s and a read of the owners every 1 s:
 * 1/3 + 1 + 1 + 1/3 + 1 ≈ 3.67 operations a second, 15 × 10 × 3.67 = 550 a second for the cluster.
 *
 * Now membership is per service: per node a heartbeat every 3 s and one read of the members every 1 s, which every
 * group's reconcile and routes reuse; per group and node a renewal every 3 s and a read of the owners every 1 s:
 * 10 × (1/3 + 1 + 15 × (1/3 + 1)) ≈ 213 a second for the cluster.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlPlaneLoadTest {
    @Test
    fun `fifteen groups on ten nodes take about 213 store operations a second, not 550`(): Unit =
        runTest(timeout = 2.minutes) {
            val store = CountingLeases(InMemoryLeaseStore(virtualClock()))
            val nodes = (1..NODES).map { "node-$it" }
            val memberships = nodes.associateWith { node ->
                ServiceMembership(
                    SERVICE,
                    node,
                    { "$node.internal:7070" },
                    store,
                    context = StandardTestDispatcher(testScheduler),
                )
            }
            val ownerships = nodes.flatMap { node ->
                (1..GROUPS).map { group ->
                    PartitionOwnership(
                        LeaseGroup(SERVICE, "context-$group"),
                        node,
                        { "$node.internal:7070" },
                        PARTITIONS,
                        store,
                        context = StandardTestDispatcher(testScheduler),
                        timeSource = testScheduler.timeSource,
                        membership = memberships.getValue(node),
                    )
                }
            }
            try {
                ownerships.forEach { it.start() }
                // Converged: every partition of every group owned by the node HRW gives it to.
                delay(30.seconds)
                ownerships.forEach { ownership ->
                    ownership.owned.assert().isEqualTo(Hrw.partitionsOf(ownership.node, nodes, PARTITIONS))
                }
                val counted = store.snapshot()
                delay(WINDOW)
                val perSecond = store.snapshot().mapValues { (name, count) ->
                    (count - counted.getOrDefault(name, 0L)).toDouble() / WINDOW.inWholeSeconds
                }.filterValues { it > 0 }

                perSecond.getValue("heartbeat").assert().isCloseTo(NODES / 3.0, within(0.05))
                perSecond.getValue("members").assert().isCloseTo(NODES * 1.0, within(0.05))
                perSecond.getValue("renew").assert().isCloseTo(NODES * GROUPS / 3.0, within(0.05))
                perSecond.getValue("owners").assert().isCloseTo(NODES * GROUPS * 1.0, within(0.05))
                perSecond.keys.assert().containsOnly("heartbeat", "members", "renew", "owners")
                val total = perSecond.values.sum()
                total.assert().isCloseTo(NODES * (1 / 3.0 + 1 + GROUPS * (1 / 3.0 + 1)), within(0.05))
                total.assert().isLessThan(BEFORE * 0.4)
            } finally {
                ownerships.forEach { it.stop() }
            }
            store.members(SERVICE).assert().isEmpty()
        }

    @Test
    fun `a group a node leaves, or is stuck in, drops off its one record at once`(): Unit = runTest(
        timeout = 1.minutes
    ) {
        val store = InMemoryLeaseStore(virtualClock())
        val membership = ServiceMembership(
            SERVICE,
            "node-1",
            { "node-1.internal:7070" },
            store,
            context = StandardTestDispatcher(testScheduler),
        )
        membership.join("$SERVICE/a") {}
        membership.join("$SERVICE/b") {}
        store.members(SERVICE).single().groups.assert().containsExactly("$SERVICE/a", "$SERVICE/b")
        membership.stuck("$SERVICE/a", true)
        store.members(SERVICE).single().groups.assert().containsExactly("$SERVICE/b")
        membership.refresh()
        membership.membersOf("$SERVICE/a").assert().isEmpty()
        membership.membersOf("$SERVICE/b").map { it.node }.assert().containsExactly("node-1")
        membership.stuck("$SERVICE/a", false)
        membership.leave("$SERVICE/b")
        store.members(SERVICE).single().groups.assert().containsExactly("$SERVICE/a")
        membership.stuck("$SERVICE/a", true)
        store.members(SERVICE).assert().isEmpty()
        membership.leave("$SERVICE/a")
        store.members(SERVICE).assert().isEmpty()
    }

    private fun within(fraction: Double) = org.assertj.core.data.Percentage.withPercentage(fraction * 100)

    /** The store's clock follows the test's virtual time. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /** Counts each operation by its name. */
    private class CountingLeases(private val delegate: LeaseStore) : LeaseStore by delegate {
        private val counts = ConcurrentHashMap<String, AtomicLong>()

        fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }

        private fun count(name: String) {
            counts.computeIfAbsent(name) { AtomicLong() }.incrementAndGet()
        }

        override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? =
            delegate.acquire(group, partition, owner, ttl).also { count("acquire") }

        override suspend fun renew(group: String, partitions: Collection<Int>, owner: String, ttl: Duration): List<Lease> =
            delegate.renew(group, partitions, owner, ttl).also { count("renew") }

        override suspend fun release(group: String, partition: Int, owner: String): Boolean =
            delegate.release(group, partition, owner).also { count("release") }

        override suspend fun owners(group: String): List<Lease> = delegate.owners(group).also { count("owners") }

        override suspend fun heartbeat(
            service: String,
            node: String,
            address: String,
            ttl: Duration,
            groups: Set<String>,
        ): Member = delegate.heartbeat(service, node, address, ttl, groups).also { count("heartbeat") }

        override suspend fun members(service: String): List<Member> =
            delegate.members(service).also { count("members") }

        override suspend fun leave(service: String, node: String) {
            delegate.leave(service, node)
            count("leave")
        }
    }

    private companion object {
        const val SERVICE = "load-service"
        const val NODES = 10
        const val GROUPS = 15
        const val PARTITIONS = 64
        val WINDOW = 60.seconds

        /** The operations a second before, as the class says: 15 × 10 × (1/3 + 1 + 1 + 1/3 + 1). */
        const val BEFORE = GROUPS * NODES * (1 / 3.0 + 1 + 1 + 1 / 3.0 + 1)
    }
}
