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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A partition whose start takes longer than the margin `leaseTtl − renewInterval`, its subscriptions slow to read their
 * parked aggregates (a cold store, a large log), on virtual time: its lease is renewed while it starts, so it is kept,
 * and the partition takes commands only once it has started.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source times the renewals.
class SlowPartitionStartTest {
    @Test
    fun `a partition slower to start than the margin should keep its lease, and take no command before it started`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val rounds = CopyOnWriteArrayList<RenewalRound>()
            val ownership = ownership(store, rounds)
            val starting = launch { ownership.start() }
            try {
                delay(5.seconds)
                // Acquired and starting: pending, it takes no command, and is not forwarded as one handed off.
                ownership.pending(0).assert().isTrue()
                ownership.accepts(0).assert().isFalse()
                ownership.runIfAccepting(0) { "ran" }.assert().isNull()
                ownership.forwardTarget(0).assert().isNull()
                ownership.owned.assert().isEmpty()
                // Past the lease's TTL, and still starting: renewed all along, so no other node may take it.
                delay(6.seconds)
                store.owners(GROUP.name).map { it.owner }.assert().containsExactly(NODE)
                store.acquire(GROUP.name, 0, "other-node", ClusterOptions().leaseTtl).assert().isNull()
                starting.join()
                ownership.owned.assert().containsExactly(0)
                ownership.pending(0).assert().isFalse()
                ownership.runIfAccepting(0) { "ran" }.assert().isEqualTo("ran")
                // Not taken for overdue once it opens: its lease was renewed while it started.
                delay(10.seconds)
                ownership.owned.assert().containsExactly(0)
                rounds.sumOf { it.lost }.assert().isZero()
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `a partition whose lease is lost while it starts should not open, and should stop`(): Unit =
        runTest(timeout = 1.minutes) {
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
            }
            // Slower still: a renewal finds the lease taken while the partition is starting.
            val ownership = ownership(store, CopyOnWriteArrayList(), start = 15.seconds)
            val starting = launch { ownership.start() }
            try {
                delay(1.seconds)
                // The store unreachable past the lease's TTL: the lease expires, and another node takes it.
                down = true
                delay(10.seconds)
                delegate.acquire(GROUP.name, 0, "other-node", ClusterOptions().leaseTtl).assert().isNotNull()
                down = false
                starting.join()
                ownership.accepts(0).assert().isFalse()
                ownership.owned.assert().isEmpty()
                delay(5.seconds)
                ownership.owned.assert().isEmpty()
                delegate.owners(GROUP.name).map { it.owner }.assert().containsExactly("other-node")
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `a partition whose start fails should be released, then acquired again`(): Unit = runTest(timeout = 1.minutes) {
        val store = InMemoryLeaseStore(virtualClock())
        val ownership = ownership(store, CopyOnWriteArrayList(), start = 1.seconds, failures = 1)
        try {
            ownership.start()
            // Its start failed: released, and no longer tracked, so the next reconcile acquires it anew.
            ownership.owned.assert().isEmpty()
            ownership.pending(0).assert().isTrue()
            store.owners(GROUP.name).assert().isEmpty()
            delay(1.minutes / 2)
            ownership.owned.assert().containsExactly(0)
        } finally {
            ownership.stop()
        }
    }

    private fun TestScope.ownership(
        store: LeaseStore,
        rounds: MutableList<RenewalRound>,
        start: Duration = START,
        failures: Int = 0,
    ): PartitionOwnership {
        val runtime = SubscriptionRuntime(
            GROUP.name,
            InMemoryEventLog(PARTITIONS),
            InMemoryCheckpointStore(),
            SlowLedger(InMemoryFailureLedger(), start, failures),
            subscriptions = listOf(Subscription("projector", SubscriptionFilter(GROUP.context)) { }),
            context = StandardTestDispatcher(testScheduler),
        )
        return PartitionOwnership(
            GROUP,
            NODE,
            { "$NODE.internal:7070" },
            PARTITIONS,
            store,
            subscriptions = runtime,
            context = StandardTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
            renewalRounds = { rounds += it },
        )
    }

    /** The store's clock follows the test's virtual time. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /**
     * A ledger slow to list a partition's parked aggregates, as a subscription reads them when its partition starts,
     * failing the first [failures] times.
     */
    private class SlowLedger(
        private val delegate: FailureLedger,
        private val latency: Duration,
        private var failures: Int,
    ) : FailureLedger by delegate {
        override suspend fun due(
            subscription: SubscriptionId,
            partitions: Collection<Int>,
            now: Long,
            after: AggregateRef?,
            limit: Int,
        ): List<ParkedAggregate> {
            if (partitions.isNotEmpty()) {
                delay(latency)
                check(failures-- <= 0) { "The ledger is unreachable." }
            }
            return delegate.due(subscription, partitions, now, after, limit)
        }
    }

    private companion object {
        const val NODE = "node-1"
        const val PARTITIONS = 1

        /** How long the partition takes to start: longer than the lease's TTL. */
        val START = 12.seconds
        val GROUP = LeaseGroup("slow-service", "slow")
    }
}
