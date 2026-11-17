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

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.Appender
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.cart
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * #6379 on virtual time: a node that lost the leases of its partitions to one slow renewal stopped them one after
 * another, each waiting for its entries in flight, and acquired none again until the last had stopped (33 s for 64
 * partitions under load in run 44040948524). The lost partitions stop together now, each acquired again once its own
 * stop ended.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source times the leases and the stops.
class LostPartitionsTest {
    private val log = InMemoryEventLog(PARTITIONS)
    private val appender = Appender(log)
    private val reading = ReadingLog(log)

    /** The aggregates whose entry a handler holds until it is cancelled: a drain that takes the whole stop timeout. */
    private val slow = ConcurrentHashMap.newKeySet<String>()
    private val holding = AtomicInteger()

    @Test
    fun `partitions lost at once come back within one stop timeout, though each one's drain takes all of it`(): Unit =
        runTest(timeout = 1.minutes) {
            val leases = LosingLeases(InMemoryLeaseStore(virtualClock()))
            val runtime = runtime()
            val ownership = ownership(leases, runtime)
            try {
                ownership.start()
                delay(5.seconds)
                (0 until PARTITIONS).forEach { ownership.accepts(it).assert().isTrue() }
                (0 until PARTITIONS).forEach { hold(it) }
                awaitHolding(PARTITIONS)

                val lost = loseAll(leases, ownership)
                awaitAccepting(ownership, 0 until PARTITIONS)

                // Virtual time: every drain takes exactly the stop timeout, so drains in series take PARTITIONS of
                // them (16 s), and drains together one, with the reconcile's wake-up and the starts.
                (testScheduler.currentTime - lost).assert().isLessThan(
                    (STOP_TIMEOUT * 2 + ClusterOptions().reconcileInterval).inWholeMilliseconds
                )
                // Each partition stopped before it started again: never two tails of one partition at once.
                (0 until PARTITIONS).forEach { reading.most(it).assert().isEqualTo(1) }
            } finally {
                slow.clear()
                ownership.stop()
            }
        }

    @Test
    fun `one lost partition's long drain holds up only that partition, which starts again once it ended`(): Unit =
        runTest(timeout = 1.minutes) {
            val leases = LosingLeases(InMemoryLeaseStore(virtualClock()))
            val runtime = runtime()
            val ownership = ownership(leases, runtime)
            try {
                ownership.start()
                delay(5.seconds)
                // Partition 0 drains first in the series the lost partitions used to stop in: the gates' map keeps
                // small partition numbers in ascending order.
                hold(0)
                awaitHolding(1)

                val lost = loseAll(leases, ownership)
                awaitAccepting(ownership, 1 until PARTITIONS)
                (testScheduler.currentTime - lost).assert().isLessThan(STOP_TIMEOUT.inWholeMilliseconds)
                ownership.accepts(0).assert().isFalse()

                awaitAccepting(ownership, listOf(0))
                (testScheduler.currentTime - lost).assert().isGreaterThanOrEqualTo(STOP_TIMEOUT.inWholeMilliseconds)
                reading.most(0).assert().isEqualTo(1)
            } finally {
                slow.clear()
                ownership.stop()
            }
        }

    @Test
    fun `a failed acquisition of one lost partition is thrown once the others stopped and came back`(): Unit = runTest {
        val gates = ConcurrentHashMap<Int, PartitionGate>()
        val subscriptions = mockk<SubscriptionRuntime>(relaxed = true)
        // Every stop takes a second: the failure of partition 1's acquisition comes before the others ended theirs.
        coEvery { subscriptions.stopPartition(any()) } coAnswers { delay(1.seconds) }
        val inner = InMemoryLeaseStore()
        val leases = object : LeaseStore by inner {
            override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
                check(partition != 1) { "The lease store is down." }
                return inner.acquire(group, partition, owner, ttl)
            }
        }
        val options = ClusterOptions()
        val runs = PartitionRuns(
            GROUP,
            NODE,
            leases,
            options,
            testScheduler.timeSource,
            options.leaseTtl - options.renewInterval,
            gates,
            listOf(subscriptions),
            null,
            emptyList(),
            partitionChanged = {},
        )
        (0 until 4).forEach {
            gates[it] = PartitionGate(testScheduler.timeSource.markNow(), 1.hours).apply { lost = true }
        }

        assertThrows<IllegalStateException> { runs.dropLost(gates.toMap(), setOf(0, 1, 2, 3), null) }
        gates.keys.assert().containsExactlyInAnyOrder(0, 2, 3)
        gates.values.forEach { it.lost.assert().isFalse() }
    }

    private fun TestScope.runtime() = SubscriptionRuntime(
        GROUP.name,
        reading,
        InMemoryCheckpointStore(),
        InMemoryFailureLedger(),
        listOf(
            Subscription("s", SubscriptionFilter("shop")) {
                if (it.stream.ref.id in slow) {
                    holding.incrementAndGet()
                    awaitCancellation()
                }
            }
        ),
        testOptions(checkpointInterval = 1.hours, stopTimeout = STOP_TIMEOUT),
        context = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.ownership(leases: LeaseStore, runtime: SubscriptionRuntime) = PartitionOwnership(
        GROUP,
        NODE,
        { "$NODE.internal:7070" },
        PARTITIONS,
        leases,
        ClusterOptions(),
        subscriptions = runtime,
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
    )

    /** An entry in [partition] whose handler holds it until its stop cancels it. */
    private suspend fun hold(partition: Int) {
        val id = generateSequence(0) { it + 1 }.map { "a$it" }
            .first { Partitioner.partitionOf(cart(it), PARTITIONS) == partition }
        slow += id
        appender.append(id)
    }

    private suspend fun awaitHolding(handlers: Int) {
        while (holding.get() < handlers) {
            delay(10.milliseconds)
        }
    }

    /**
     * Renewals find every lease lost until the node stopped taking commands for all of them; returns the virtual time
     * it did. Renewing works again from then on, so the node can take them back.
     */
    private suspend fun TestScope.loseAll(leases: LosingLeases, ownership: PartitionOwnership): Long {
        leases.losing = true
        while ((0 until PARTITIONS).any { ownership.accepts(it) }) {
            delay(10.milliseconds)
        }
        leases.losing = false
        return testScheduler.currentTime
    }

    private suspend fun awaitAccepting(ownership: PartitionOwnership, partitions: Iterable<Int>) {
        while (partitions.any { !ownership.accepts(it) }) {
            delay(10.milliseconds)
        }
    }

    /** The store's clock follows the test's virtual time. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /** Renewing finds every lease lost while [losing], as after a store that stalled past the margin. */
    private class LosingLeases(private val delegate: LeaseStore) : LeaseStore by delegate {
        @Volatile
        var losing = false

        override suspend fun renew(group: String, partitions: Collection<Int>, owner: String, ttl: Duration): List<Lease> =
            if (losing) emptyList() else delegate.renew(group, partitions, owner, ttl)
    }

    /** A log that counts the tails of each partition open at once, and the most there were. */
    private class ReadingLog(private val delegate: EventLog) : EventLog by delegate {
        private val open = ConcurrentHashMap<Int, AtomicInteger>()
        private val mostOpen = ConcurrentHashMap<Int, Int>()

        fun most(partition: Int): Int = mostOpen[partition] ?: 0

        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
            val now = open.computeIfAbsent(partition) { AtomicInteger() }.incrementAndGet()
            mostOpen.merge(partition, now, ::maxOf)
            try {
                emitAll(delegate.tail(partition, after))
            } finally {
                open.getValue(partition).decrementAndGet()
            }
        }
    }

    private companion object {
        const val PARTITIONS = 8
        const val NODE = "node-1"
        val STOP_TIMEOUT = 2.seconds
        val GROUP = LeaseGroup(SERVICE, "lost")
    }
}
