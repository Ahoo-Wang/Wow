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
 * A node's catch-up permits ([SubscriptionOptions.maxCatchUps]): a partition behind the head of its log reads its first
 * page ([FREE_CATCH_UP] entries) at once, and needs a permit only for the rest. A partition within one page, as a
 * hand-over leaves it, never waits for another's catch-up (#6407). One further behind waits for a permit at the page's
 * end, then takes every entry. A partition gives its permit back once its subscriptions have taken the entries up to
 * the head it started behind, or have paused on a systemic failure.
 */
class CatchUpPermitsTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val appender = Appender(log)
    private val handled = Handled()

    /** One permit for the node: a single partition reads past its first page at a time. */
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

    @Test
    fun `a partition behind its head by less than a page should read it while another holds every permit`(): Unit =
        runBlocking {
            val zero = idsIn(0, ENTRIES)
            zero.forEach { appender.append(it) }
            // Another partition's catch-up holds the node's only permit.
            permits.acquire()
            val runtime = runtime(projector())
            runtime.start()
            try {
                runtime.startPartition(0)
                eventually { handled.size == ENTRIES }
                handled.entries.map { it.first }.toSet().assert().isEqualTo(zero.toSet())
                permits.availablePermits.assert().isZero()
            } finally {
                permits.release()
                runtime.stop()
            }
        }

    @Test
    fun `a partition behind its head by more than a page should read the page, then wait for a permit`(): Unit =
        runBlocking {
            val zero = idsIn(0, FREE_CATCH_UP + ENTRIES)
            zero.forEach { appender.append(it) }
            permits.acquire()
            val runtime = runtime(projector())
            runtime.start()
            try {
                runtime.startPartition(0)
                // Exactly one page: the one subscription starts joined (every cursor at 0, the shared tail's start),
                // and with room for every entry it is never detached, so the shared tail is the partition's only
                // reader and takes the page alone.
                eventually { handled.size == FREE_CATCH_UP }
                delay(PAST_THE_PAGE)
                handled.size.assert().isEqualTo(FREE_CATCH_UP)
                runtime.positions().single { it.partition == 0 }.watermark.assert().isEqualTo(FREE_CATCH_UP.toLong())

                permits.release()
                eventually { handled.size == FREE_CATCH_UP + ENTRIES }
                // The read the page ended went on from the subscription's cursor: no entry lost, none twice.
                sequencesOf(zero).assert().isEqualTo((1L..FREE_CATCH_UP + ENTRIES).toList())
                eventually { permits.availablePermits == 1 }
            } finally {
                runtime.stop()
            }
        }

    @Test
    fun `a partition past its page should wait for a permit another holds, then take everything`(): Unit =
        runBlocking {
            val (zero, one) = idsIn(0, FREE_CATCH_UP + ENTRIES) to idsIn(1, FREE_CATCH_UP + ENTRIES)
            val past = zero.drop(FREE_CATCH_UP).toSet()
            val slow = CompletableDeferred<Unit>()
            val runtime = runtime(
                Subscription("projector", SubscriptionFilter("shop")) {
                    if (it.ref.id in past) slow.await()
                    handled.record(it)
                },
                // Past its page, partition 0 waits for its handler: behind its head, it keeps the permit.
                testOptions(maxInFlight = 2),
            )
            (zero + one).forEach { appender.append(it) }
            runtime.start()
            try {
                runtime.startPartition(0)
                eventually { permits.availablePermits == 0 }
                runtime.startPartition(1)
                // Partition 1 reads its page at once, then stands short of its head while partition 0 holds the
                // permit. How far into the page it gets is not fixed: a worker detached for want of room reads on its
                // own, and its read and the shared tail's share the one page.
                eventually { handled.entries.any { it.first in one } }
                delay(PAST_THE_PAGE)
                handled.entries.count { it.first in one }.assert().isLessThan(FREE_CATCH_UP + ENTRIES)

                slow.complete(Unit)
                eventually { handled.size == 2 * (FREE_CATCH_UP + ENTRIES) }
                // Each partition's readers, a detached worker's own read among them, went on from their cursors.
                sequencesOf(zero).assert().isEqualTo((1L..FREE_CATCH_UP + ENTRIES).toList())
                sequencesOf(one).assert().isEqualTo((1L..FREE_CATCH_UP + ENTRIES).toList())
                eventually { permits.availablePermits == 1 }
            } finally {
                runtime.stop()
            }
        }

    @Test
    fun `a partition behind by less than a page should read its backlog and every new entry without a permit`(): Unit =
        runBlocking {
            val ids = idsIn(0, ENTRIES + FREE_CATCH_UP + ENTRIES)
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
                // The new entries are past the head the partition opened behind: live, they spend no page.
                eventually { handled.size == ids.size }
                sequencesOf(ids).assert().isEqualTo((1L..ids.size).toList())
                permits.availablePermits.assert().isZero()
            } finally {
                permits.release()
                runtime.stop()
            }
        }

    @Test
    fun `a partition behind by more than a page should stop at it while new entries arrive`(): Unit = runBlocking {
        val ids = idsIn(0, FREE_CATCH_UP + ENTRIES + ENTRIES)
        val (backlog, live) = ids.take(FREE_CATCH_UP + ENTRIES) to ids.drop(FREE_CATCH_UP + ENTRIES)
        val held = HeldLog()
        backlog.forEach { held.appender.append(it) }
        permits.acquire()
        val runtime = runtime(projector(), log = held.log)
        runtime.start()
        try {
            runtime.startPartition(0)
            held.reached.await()
            live.forEach { held.appender.append(it) }
            held.release.complete(Unit)
            // Exactly the page, as with no entry arriving: the one subscription's shared tail is the only reader.
            eventually { handled.size == FREE_CATCH_UP }
            delay(PAST_THE_PAGE)
            handled.size.assert().isEqualTo(FREE_CATCH_UP)

            permits.release()
            eventually { handled.size == ids.size }
            sequencesOf(ids).assert().isEqualTo((1L..ids.size).toList())
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `no more partitions than the permits should read past their page at once`(): Unit = runBlocking {
        val partitions = 4
        val gate = CompletableDeferred<Unit>()
        val readingPast = ConcurrentHashMap.newKeySet<Int>()
        // A read that goes past its partition's page waits for the gate at the second entry past it: the log emits
        // the first one to a partition without a permit too, which refuses it and ends its read there. A partition
        // holding a permit keeps it meanwhile, its subscriptions short of its head.
        val memory = InMemoryEventLog(partitions)
        val wide = object : EventLog by memory {
            override fun tail(partition: Int, after: Long?): Flow<LogEntry> = memory.tail(partition, after).onEach {
                if (it.position.sequence > FREE_CATCH_UP + 1) {
                    readingPast += partition
                    gate.await()
                }
            }
        }
        val ids = (0 until partitions).map { idsIn(it, FREE_CATCH_UP + ENTRIES, partitions) }
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
            // Exactly two: a partition's readers reach an entry past its page only once it holds a permit, and each
            // holder's read then waits at the gate, so it keeps its permit and the other two stand at their page's
            // end.
            eventually { readingPast.size == 2 }
            delay(PAST_THE_PAGE)
            readingPast.size.assert().isEqualTo(2)
            two.availablePermits.assert().isZero()

            gate.complete(Unit)
            eventually { handled.size == partitions * (FREE_CATCH_UP + ENTRIES) }
            ids.forEach { sequencesOf(it).assert().isEqualTo((1L..FREE_CATCH_UP + ENTRIES).toList()) }
            eventually { two.availablePermits == 2 }
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `a partition whose subscription pauses on a systemic failure should give its permit back`(): Unit =
        runBlocking {
            val (zero, one) = idsIn(0, FREE_CATCH_UP + ENTRIES) to idsIn(1, FREE_CATCH_UP + ENTRIES)
            val failing = zero.drop(FREE_CATCH_UP).toSet()
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
                // Paused past its page, partition 0 gives its permit back: partition 1 catches up meanwhile.
                eventually { handled.entries.count { it.first in one } == FREE_CATCH_UP + ENTRIES }
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

        /** Long enough for a partition that read past its page without a permit to have taken its entries. */
        val PAST_THE_PAGE = 200.milliseconds
    }
}
