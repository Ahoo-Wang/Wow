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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * A node's catch-up permits ([SubscriptionOptions.maxCatchUps]): a partition behind the head of its log by
 * [SMALL_BACKLOG] or less, as a hand-over leaves it, reads at once and never waits for another's catch-up (#6407). One
 * further behind waits for a permit before its first entry, then takes every entry. The backlog is measured at the head
 * the partition opened behind: entries committed after it do not count. A partition gives its permit back once its
 * subscriptions have taken the entries up to that head, or have paused on a systemic failure.
 */
class CatchUpPermitsTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val appender = Appender(log)
    private val handled = Handled()

    /** One permit for the node: a single partition catches up at a time. */
    private val permits = Semaphore(1)

    private fun idsIn(partition: Int, count: Int, partitions: Int = 2): List<String> =
        generateSequence(0) { it + 1 }.map { "a$it" }
            .filter { Partitioner.partitionOf(cart(it), partitions) == partition }
            .take(count).toList()

    /** The sequences handled of [ids], in order: 1 to their number when none was lost and none handled twice. */
    private fun sequencesOf(ids: Collection<String>): List<Long> =
        handled.entries.filter { it.first in ids }.mapNotNull { it.third }.sorted()

    private fun runtime(
        subscription: Subscription,
        options: SubscriptionOptions = testOptions(),
        log: EventLog = this.log,
    ) =
        SubscriptionRuntime(
            "group",
            log,
            InMemoryCheckpointStore(),
            InMemoryFailureLedger(),
            listOf(subscription),
            options,
            catchUps = permits,
        )

    private fun projector() = Subscription("projector", SubscriptionFilter("shop")) { handled.record(it) }

    /** Starts partition 0 behind by [backlog] entries while another partition's catch-up holds the only permit. */
    private suspend fun behindBy(backlog: Int, block: suspend (SubscriptionRuntime, List<String>) -> Unit) {
        val zero = idsIn(0, backlog)
        zero.forEach { appender.append(it) }
        permits.acquire()
        val runtime = runtime(projector())
        runtime.start()
        try {
            runtime.startPartition(0)
            block(runtime, zero)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `a partition behind its head by less than a page should read it while another holds every permit`(): Unit =
        runBlocking {
            behindBy(ENTRIES) { _, zero ->
                eventually { handled.size == ENTRIES }
                handled.entries.map { it.first }.toSet().assert().isEqualTo(zero.toSet())
                permits.availablePermits.assert().isZero()
                permits.release()
            }
        }

    @Test
    fun `a partition behind its head by exactly a page should read it without a permit`(): Unit = runBlocking {
        behindBy(SMALL_BACKLOG) { _, zero ->
            eventually { handled.size == SMALL_BACKLOG }
            sequencesOf(zero).assert().isEqualTo((1L..SMALL_BACKLOG).toList())
            permits.availablePermits.assert().isZero()
            permits.release()
        }
    }

    @Test
    fun `a partition behind its head by a page and one should read nothing until it holds a permit`(): Unit =
        runBlocking {
            behindBy(SMALL_BACKLOG + 1) { runtime, zero ->
                delay(UNPERMITTED)
                handled.size.assert().isZero()
                runtime.positions().single { it.partition == 0 }.watermark.assert().isZero()

                permits.release()
                eventually { handled.size == SMALL_BACKLOG + 1 }
                sequencesOf(zero).assert().isEqualTo((1L..SMALL_BACKLOG + 1).toList())
                eventually { permits.availablePermits == 1 }
            }
        }

    @Test
    fun `a partition past a page should wait for a permit another holds, then take everything`(): Unit =
        runBlocking {
            val (zero, one) = idsIn(0, SMALL_BACKLOG + ENTRIES) to idsIn(1, SMALL_BACKLOG + ENTRIES)
            val first = zero.first()
            val slow = CompletableDeferred<Unit>()
            val runtime = runtime(
                Subscription("projector", SubscriptionFilter("shop")) {
                    if (it.ref.id == first) slow.await()
                    handled.record(it)
                },
                // Caught up means taken: with room for two entries in flight, partition 0's worker takes no further
                // than its first entries while that handler waits, so it stays short of its head and keeps the permit.
                testOptions(maxInFlight = 2),
            )
            (zero + one).forEach { appender.append(it) }
            runtime.start()
            try {
                runtime.startPartition(0)
                eventually { permits.availablePermits == 0 }
                runtime.startPartition(1)
                delay(UNPERMITTED)
                handled.entries.count { it.first in one }.assert().isZero()

                slow.complete(Unit)
                eventually { handled.size == 2 * (SMALL_BACKLOG + ENTRIES) }
                sequencesOf(zero).assert().isEqualTo((1L..SMALL_BACKLOG + ENTRIES).toList())
                sequencesOf(one).assert().isEqualTo((1L..SMALL_BACKLOG + ENTRIES).toList())
                eventually { permits.availablePermits == 1 }
            } finally {
                runtime.stop()
            }
        }

    @Test
    fun `a partition behind by less than a page should read its backlog and every new entry without a permit`(): Unit =
        runBlocking {
            val ids = idsIn(0, ENTRIES + SMALL_BACKLOG + ENTRIES)
            val (backlog, live) = ids.take(ENTRIES) to ids.drop(ENTRIES)
            val held = HeldLog()
            backlog.forEach { held.appender.append(it) }
            permits.acquire()
            val runtime = runtime(projector(), log = held.log)
            runtime.start()
            try {
                runtime.startPartition(0)
                // Held at its first entry, past its head's read: more than a page of new entries commits meanwhile.
                held.reached.await()
                live.forEach { held.appender.append(it) }
                held.release.complete(Unit)
                // The new entries are past the head the partition opened behind: live, they do not count.
                eventually { handled.size == ids.size }
                sequencesOf(ids).assert().isEqualTo((1L..ids.size).toList())
                permits.availablePermits.assert().isZero()
            } finally {
                permits.release()
                runtime.stop()
            }
        }

    @Test
    fun `a partition whose log's tail ends should read what it gave and nothing after`(): Unit = runBlocking {
        val ids = idsIn(0, ENTRIES + ENTRIES)
        val memory = InMemoryEventLog(partitions = 2)
        // The tail gives the backlog, then ends, as a log that closes would.
        val ending = object : EventLog by memory {
            override fun tail(partition: Int, after: Long?): Flow<LogEntry> =
                memory.tail(partition, after).take(ENTRIES)
        }
        val appender = Appender(ending)
        ids.take(ENTRIES).forEach { appender.append(it) }
        val runtime = runtime(projector(), log = ending)
        runtime.start()
        try {
            runtime.startPartition(0)
            eventually { handled.size == ENTRIES }
            ids.drop(ENTRIES).forEach { appender.append(it) }
            delay(UNPERMITTED)
            // The partition's one reader was its shared tail: its reads ended with the tail.
            sequencesOf(ids).assert().isEqualTo((1L..ENTRIES).toList())
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `no more partitions than the permits should catch up at once`(): Unit = runBlocking {
        val partitions = 4
        val gate = CompletableDeferred<Unit>()
        val reading = ConcurrentHashMap.newKeySet<Int>()
        // Each partition's read waits for the gate at its first entry: a partition holding a permit keeps it meanwhile,
        // its subscriptions short of its head.
        val memory = InMemoryEventLog(partitions)
        val wide = object : EventLog by memory {
            override fun tail(partition: Int, after: Long?): Flow<LogEntry> = memory.tail(partition, after).onEach {
                reading += partition
                gate.await()
            }
        }
        val ids = (0 until partitions).map { idsIn(it, SMALL_BACKLOG + ENTRIES, partitions) }
        val two = Semaphore(2)
        val runtime = SubscriptionRuntime(
            "group",
            wide,
            InMemoryCheckpointStore(),
            InMemoryFailureLedger(),
            listOf(projector()),
            testOptions(),
            catchUps = two,
        )
        Appender(wide).let { appender -> ids.flatten().forEach { appender.append(it) } }
        runtime.start()
        try {
            (0 until partitions).forEach { runtime.startPartition(it) }
            // Exactly two: a partition past a page reads its first entry only once it holds a permit, and each
            // holder's read then waits at the gate, so it keeps its permit and the other two read nothing.
            eventually { reading.size == 2 }
            delay(UNPERMITTED)
            reading.size.assert().isEqualTo(2)
            two.availablePermits.assert().isZero()

            gate.complete(Unit)
            eventually { handled.size == partitions * (SMALL_BACKLOG + ENTRIES) }
            ids.forEach { sequencesOf(it).assert().isEqualTo((1L..SMALL_BACKLOG + ENTRIES).toList()) }
            eventually { two.availablePermits == 2 }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `a partition whose subscription pauses on a systemic failure should give its permit back`(): Unit =
        runBlocking {
            val (zero, one) = idsIn(0, SMALL_BACKLOG + ENTRIES) to idsIn(1, SMALL_BACKLOG + ENTRIES)
            val failing = zero.drop(SMALL_BACKLOG).toSet()
            val runtime = runtime(
                Subscription("projector", SubscriptionFilter("shop")) {
                    if (it.ref.id in failing) throw StorageUnavailableException("The view store is unavailable.")
                    handled.record(it)
                },
                testOptions(maxInFlight = 3).copyWith(
                    systemic = SystemicFailureOptions(
                        failingAggregates = 2,
                        initialBackoff = 50.milliseconds,
                        maxBackoff = 100.milliseconds,
                    ),
                ),
            )
            (zero + one).forEach { appender.append(it) }
            runtime.start()
            try {
                runtime.startPartition(0)
                eventually { runtime.paused().isNotEmpty() }
                runtime.startPartition(1)
                // Paused short of its head, partition 0 gives its permit back: partition 1 catches up meanwhile.
                eventually { handled.entries.count { it.first in one } == SMALL_BACKLOG + ENTRIES }
                runtime.paused().single().partition.assert().isZero()
            } finally {
                runtime.stop()
            }
        }

    /**
     * A log of two partitions whose first read of partition 0 waits at its first entry for [release]: the partition
     * has read its head by then, so what is appended meanwhile is live to it.
     */
    private class HeldLog {
        private val memory = InMemoryEventLog(partitions = 2)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val log: EventLog = object : EventLog by memory {
            override fun tail(partition: Int, after: Long?): Flow<LogEntry> = memory.tail(partition, after).onEach {
                if (partition == 0 && it.position.sequence == 1L) {
                    reached.complete(Unit)
                    release.await()
                }
            }
        }
        val appender = Appender(log)
    }

    private companion object {
        const val ENTRIES = 6

        /** Long enough for a partition that read without a permit to have taken its entries. */
        val UNPERMITTED = 200.milliseconds
    }
}
