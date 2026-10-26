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

package me.ahoo.wow.runtime.log

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class PartitionAppendQueueTest {
    private val type = AggregateType("bank", "account")

    /** Aggregate ids that all land in partition 0 of [partitions]. */
    private fun refsOfOnePartition(count: Int, partitions: Int = PARTITIONS): List<AggregateRef> =
        generateSequence(0) { it + 1 }
            .map { type.ref("tenant", "account-$it") }
            .filter { Partitioner.partitionOf(it, partitions) == 0 }
            .take(count)
            .toList()

    private fun stream(ref: AggregateRef, version: Int, requestId: String = "${ref.id}-$version") = EventStreamRecord(
        ref = ref,
        version = version,
        commandId = "command-$requestId",
        requestId = requestId,
        commandType = "bank.Deposit",
        commitTime = 1L,
        events = listOf(EventRecord("bank.Deposited", 1, """{"amount":1}""")),
        scope = Scope.NONE,
    )

    @Test
    fun `appends queued meanwhile are committed together, each caller with its own outcome`(): Unit = runBlocking {
        val log = GatedLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions())
        val refs = refsOfOnePartition(5)
        val first = async(Dispatchers.Default) { queue.append(stream(refs[0], 1)) }
        log.entered.await()
        // One at a time, so the queue order is known.
        val rest = refs.drop(1).mapIndexed { index, ref ->
            async(Dispatchers.Default) { queue.append(stream(ref, 1)) }.also { queue.queuedSoon(index + 1) }
        }
        log.release.complete(Unit)

        val outcomes = (listOf(first) + rest).awaitAll()

        log.batches.map { batch -> batch.map { it.ref } }.assert().containsExactly(refs.take(1), refs.drop(1))
        outcomes.forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        val sequences = outcomes.map { (it as AppendOutcome.Committed).position.sequence }
        sequences.assert().containsExactly(1L, 2L, 3L, 4L, 5L)
        queue.close()
    }

    @Test
    fun `a conflict or a duplicate inside a batch fails only its own caller`(): Unit = runBlocking {
        val inner = InMemoryEventLog(PARTITIONS)
        val (fresh, behind, duplicate) = refsOfOnePartition(3)
        inner.append(stream(behind, 1))
        inner.append(stream(duplicate, 1, requestId = "taken"))
        val log = GatedLog(inner)
        val queue = PartitionAppendQueue(log, AppendQueueOptions())
        val blocker = refsOfOnePartition(4).last()
        val blocked = async(Dispatchers.Default) { queue.append(stream(blocker, 1)) }
        log.entered.await()
        val outcomes = listOf(
            stream(fresh, 1),
            stream(behind, 1, requestId = "another"),
            stream(duplicate, 2, requestId = "taken"),
        ).map { async(Dispatchers.Default) { queue.append(it) } }
        queue.queuedSoon(outcomes.size)
        log.release.complete(Unit)

        blocked.await().assert().isInstanceOf(AppendOutcome.Committed::class.java)
        val (committed, conflict, alreadyCommitted) = outcomes.awaitAll()
        log.batches.last().size.assert().isEqualTo(3)
        committed.assert().isInstanceOf(AppendOutcome.Committed::class.java)
        conflict.assert().isEqualTo(AppendOutcome.Conflict(1))
        (alreadyCommitted as AppendOutcome.AlreadyCommitted).existing.ref.assert().isEqualTo(duplicate)
        queue.close()
    }

    @Test
    fun `appends of one aggregate go in separate batches, in order`(): Unit = runBlocking {
        val log = GatedLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions())
        val (blocker, ref) = refsOfOnePartition(2)
        val blocked = async(Dispatchers.Default) { queue.append(stream(blocker, 1)) }
        log.entered.await()
        val v1 = async(Dispatchers.Default) { queue.append(stream(ref, 1)) }
        queue.queuedSoon(1)
        val v2 = async(Dispatchers.Default) { queue.append(stream(ref, 2)) }
        queue.queuedSoon(2)
        log.release.complete(Unit)

        listOf(blocked, v1, v2).awaitAll().forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        log.batches.map { batch -> batch.map { it.ref to it.version } }
            .assert().containsExactly(listOf(blocker to 1), listOf(ref to 1), listOf(ref to 2))
        queue.close()
    }

    @Test
    fun `a failing batch fails each of its callers, and the lane goes on`(): Unit = runBlocking {
        val log = GatedLog(InMemoryEventLog(PARTITIONS), failBatch = 2)
        val queue = PartitionAppendQueue(log, AppendQueueOptions())
        val refs = refsOfOnePartition(4)
        val blocked = async(Dispatchers.Default) { queue.append(stream(refs[0], 1)) }
        log.entered.await()
        val failing = refs.subList(
            1,
            3
        ).map { ref -> async(Dispatchers.Default) { runCatching { queue.append(stream(ref, 1)) } } }
        queue.queuedSoon(failing.size)
        log.release.complete(Unit)

        blocked.await().assert().isInstanceOf(AppendOutcome.Committed::class.java)
        failing.awaitAll().forEach {
            it.exceptionOrNull()!!.message.assert().isEqualTo("The batch failed.")
        }
        queue.append(stream(refs[3], 1)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        queue.close()
    }

    @Test
    fun `a caller cancelled while queued is never appended`(): Unit = runBlocking {
        val log = GatedLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions())
        val (blocker, cancelled, kept) = refsOfOnePartition(3)
        val blocked = async(Dispatchers.Default) { queue.append(stream(blocker, 1)) }
        log.entered.await()
        val withdrawn = launch(Dispatchers.Default) { queue.append(stream(cancelled, 1)) }
        queue.queuedSoon(1)
        withdrawn.cancelAndJoin()
        val after = async(Dispatchers.Default) { queue.append(stream(kept, 1)) }
        queue.queuedSoon(1)
        log.release.complete(Unit)

        blocked.await().assert().isInstanceOf(AppendOutcome.Committed::class.java)
        after.await().assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.batches.flatten().map { it.ref }.assert().containsExactly(blocker, kept)
        log.read(cancelled).toList().assert().isEmpty()
        queue.close()
    }

    @Test
    fun `a full lane makes the next caller wait for room instead of queueing without bound`(): Unit = runBlocking {
        val log = GatedLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions(), capacity = 1)
        val refs = refsOfOnePartition(4)
        val (blocker, queued, cancelled) = refs
        val waiting = refs[3]
        val blocked = async(Dispatchers.Default) { queue.append(stream(blocker, 1)) }
        log.entered.await()
        val inLane = async(Dispatchers.Default) { queue.append(stream(queued, 1)) }
        queue.queuedSoon(1)
        // The lane holds one: this caller waits for room, and cancelled meanwhile, it is never appended.
        val withdrawn = launch(Dispatchers.Default) { queue.append(stream(cancelled, 1)) }
        queue.queuedSoon(2)
        withdrawn.cancelAndJoin()
        val behind = async(Dispatchers.Default) { queue.append(stream(waiting, 1)) }
        queue.queuedSoon(2)
        log.release.complete(Unit)

        listOf(blocked, inLane, behind).awaitAll()
            .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        log.batches.flatten().map { it.ref }.assert().containsExactlyInAnyOrder(blocker, queued, waiting)
        log.read(cancelled).toList().assert().isEmpty()
        queue.close()
    }

    @Test
    fun `a lane holds at least one append`() {
        assertThrows<IllegalArgumentException> { PartitionAppendQueue(InMemoryEventLog(PARTITIONS), capacity = 0) }
    }

    @Test
    fun `a caller cancelled once its append is in a batch leaves it committed`(): Unit = runBlocking {
        val log = GatedLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions())
        val ref = refsOfOnePartition(1).single()
        val caller = launch(Dispatchers.Default) { queue.append(stream(ref, 1)) }
        log.entered.await()
        caller.cancelAndJoin()
        log.release.complete(Unit)
        queue.close()

        log.read(ref).toList().map { it.version }.assert().containsExactly(1)
    }

    @Test
    fun `close commits what is queued and refuses later appends`(): Unit = runBlocking {
        val log = GatedLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions())
        val refs = refsOfOnePartition(2)
        // One batch in the log, the other append queued behind it, before the queue closes: launched together, the
        // second could still be on its way to the queue when it closed, and be refused.
        val first = async(Dispatchers.Default) { queue.append(stream(refs[0], 1)) }
        log.entered.await()
        val second = async(Dispatchers.Default) { queue.append(stream(refs[1], 1)) }
        queue.queuedSoon(1)
        val appends = listOf(first, second)
        val closing = launch(Dispatchers.Default) { queue.close() }
        log.release.complete(Unit)
        closing.join()

        appends.awaitAll().forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        val late = refsOfOnePartition(3).last()
        assertThrows<IllegalStateException> { queue.append(stream(late, 1)) }
        assertThrows<IllegalStateException> { queue.appendAll(listOf(stream(late, 1) to null)) }
        queue.queued.assert().isZero()
    }

    @Test
    fun `appendAll queues each stream on its own partition's lane`(): Unit = runBlocking {
        val log = RecordingLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions())
        val streams = (0 until 8).map { stream(type.ref("tenant", "spread-$it"), 1) }

        val outcomes = queue.appendAll(streams.map { it to null })

        outcomes.forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        log.batches.forEach { batch ->
            batch.map { Partitioner.partitionOf(it.ref, PARTITIONS) }.toSet().size.assert().isEqualTo(1)
        }
        queue.close()
    }

    @Test
    fun `one aggregate's streams of one call go into one batch, in order, and commit as a prefix`(): Unit =
        runBlocking {
            // Roadmap §6 item 10: a kernel's batch of an aggregate's commands reaches the log in one group commit.
            val log = RecordingLog(InMemoryEventLog(PARTITIONS))
            val queue = PartitionAppendQueue(log, AppendQueueOptions())
            val (hot, other) = refsOfOnePartition(2)
            queue.append(stream(hot, 1), null)
            val outcomes = queue.appendAll(
                listOf(
                    stream(hot, 2) to null,
                    stream(other, 1) to null,
                    stream(hot, 3) to null,
                    stream(hot, 4) to null
                ),
            )
            outcomes.forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
            // The other aggregate's unit may commit in a batch of its own; the hot one's streams are in one, in order.
            log.batches.single { batch -> batch.any { it.ref == hot && it.version == 2 } }
                .filter { it.ref == hot }.map { it.version }.assert().containsExactly(2, 3, 4)
            // A conflict at the first stops the rest of the aggregate's.
            val stopped = queue.appendAll(listOf(stream(hot, 4, "again") to null, stream(hot, 5) to null))
            stopped.assert().containsExactly(AppendOutcome.Conflict(4), AppendOutcome.Conflict(4))
            queue.close()
        }

    @Test
    fun `a batch takes at least one append`() {
        assertThrows<IllegalArgumentException> { AppendQueueOptions(maxBatch = 0) }
    }

    @Test
    fun `a batch takes at most maxBatch appends`(): Unit = runBlocking {
        val log = GatedLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions(maxBatch = 2))
        val refs = refsOfOnePartition(6)
        val first = async(Dispatchers.Default) { queue.append(stream(refs[0], 1)) }
        log.entered.await()
        val rest = refs.drop(1).map { ref -> async(Dispatchers.Default) { queue.append(stream(ref, 1)) } }
        queue.queuedSoon(rest.size)
        log.release.complete(Unit)

        (listOf(first) + rest).awaitAll()
        log.batches.map { it.size }.assert().containsExactly(1, 2, 2, 1)
        queue.close()
    }

    @Test
    fun `many aggregates appending concurrently all commit, in batches, at a sane rate`(): Unit = runBlocking {
        val log = RecordingLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions())
        val positions = CopyOnWriteArrayList<LogPosition>()
        val started = System.nanoTime()
        withTimeout(30.seconds) {
            coroutineScope {
                repeat(AGGREGATES) { index ->
                    launch(Dispatchers.Default) {
                        val ref = type.ref("tenant", "load-$index")
                        for (version in 1..VERSIONS) {
                            positions += (queue.append(stream(ref, version)) as AppendOutcome.Committed).position
                        }
                    }
                }
            }
        }
        val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND
        val total = AGGREGATES * VERSIONS
        println(
            "PartitionAppendQueue in memory: $total appends in ${"%.2f".format(seconds)} s " +
                "(${"%.0f".format(total / seconds)}/s), ${log.batches.size} batches",
        )

        log.batches.sumOf { it.size }.assert().isEqualTo(total)
        (log.batches.size < total).assert().isTrue()
        positions.groupBy { it.partition }.values.forEach { inPartition ->
            inPartition.map { it.sequence }.sorted().assert().isEqualTo((1L..inPartition.size).toList())
        }
        queue.close()
    }

    /** Waits until [count] appends are queued behind the batch in flight. */
    private suspend fun PartitionAppendQueue.queuedSoon(count: Int) {
        withTimeout(5.seconds) {
            while (queued < count) {
                yield()
            }
        }
    }

    /** Records every batch it commits. */
    /** The first aggregate found in [partition]. */
    private fun refIn(partition: Int, from: Int = 0): AggregateRef = generateSequence(from) { it + 1 }
        .map { type.ref("tenant", "account-$it") }
        .first { Partitioner.partitionOf(it, PARTITIONS) == partition }

    private fun partitionOf(stream: EventStreamRecord) = Partitioner.partitionOf(stream.ref, PARTITIONS)

    @Test
    fun `groups several partitions handed meanwhile commit with one call, each caller with its own outcome`(): Unit =
        runBlocking {
            // Decision D275: the store's fixed cost of a call is shared by the partitions' small groups.
            val log = GatedLog(InMemoryEventLog(PARTITIONS), acrossPartitions = true)
            // One committer, so the call in flight holds the others' groups back until they merge.
            val queue = PartitionAppendQueue(log, AppendQueueOptions(mergers = 1))
            queue.merging.assert().isTrue()
            val first = refIn(0)
            val sameLane = refIn(0, from = 1_000)
            val held = async(Dispatchers.Default) { queue.append(stream(first, 1)) }
            withTimeout(5.seconds) { log.entered.await() }
            val others = (1 until PARTITIONS).map { partition ->
                async(Dispatchers.Default) { queue.append(stream(refIn(partition), 1)) }
            }
            val behind = async(Dispatchers.Default) { queue.append(stream(sameLane, 1)) }
            // The other partitions' groups are handed to the committer; partition 0's next waits for its first.
            withTimeout(5.seconds) { while (queue.queued != 1) delay(1) }
            log.release.complete(Unit)
            (listOf(held, behind) + others).forEach {
                it.await().assert().isInstanceOf(
                    AppendOutcome.Committed::class.java
                )
            }
            log.batches.first().map { it.ref }.assert().containsExactly(first)
            log.batches[1].map(::partitionOf).toSet().assert().containsAll((1 until PARTITIONS).toList())
            // One group of a partition in flight at a time: partition 0's two never share a call.
            log.batches.none { batch -> batch.count { partitionOf(it) == 0 } > 1 }.assert().isTrue()
            queue.close()
        }

    @Test
    fun `a merged call that throws fails every caller of its groups, and the lanes go on`(): Unit = runBlocking {
        // A log that throws on any call holding a stream of partition 1: whichever groups merged with it fail too.
        val log = object : RecordingLog(InMemoryEventLog(PARTITIONS), appendsAcrossPartitions = true) {
            var refusing = true

            override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
                if (refusing && streams.any { partitionOf(it.first) == 1 }) error("The batch failed.")
                return super.appendAll(streams)
            }
        }
        val queue = PartitionAppendQueue(log, AppendQueueOptions())
        val outcomes = (0 until PARTITIONS).map { partition ->
            async(Dispatchers.Default) { runCatching { queue.append(stream(refIn(partition), 1)) } }
        }.awaitAll()
        outcomes[1].exceptionOrNull()?.message.assert().isEqualTo("The batch failed.")
        outcomes.forEach { outcome ->
            outcome.exceptionOrNull()?.message?.assert()?.isEqualTo("The batch failed.")
                ?: outcome.getOrThrow().assert().isInstanceOf(AppendOutcome.Committed::class.java)
        }
        log.refusing = false
        queue.append(stream(refIn(1), 1)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        queue.close()
    }

    @Test
    fun `a merged call takes at most mergeMax streams, the rest the next call`(): Unit = runBlocking {
        val log = GatedLog(InMemoryEventLog(PARTITIONS), acrossPartitions = true)
        val queue = PartitionAppendQueue(log, AppendQueueOptions(maxBatch = 1, mergers = 1, mergeMax = 2))
        val held = async(Dispatchers.Default) { queue.append(stream(refIn(0), 1)) }
        withTimeout(5.seconds) { log.entered.await() }
        val others = (1 until PARTITIONS).map { partition ->
            async(Dispatchers.Default) { queue.append(stream(refIn(partition), 1)) }
        }
        withTimeout(5.seconds) { while (queue.queued != 0) delay(1) }
        log.release.complete(Unit)
        (listOf(held) + others).forEach { it.await().assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        log.batches.all { it.size <= 2 }.assert().isTrue()
        queue.close()
    }

    @Test
    fun `groups merge exactly when the log takes several partitions a call`(): Unit = runBlocking {
        val across = RecordingLog(InMemoryEventLog(PARTITIONS), appendsAcrossPartitions = true)
        PartitionAppendQueue(across, AppendQueueOptions()).apply { merging.assert().isTrue() }.close()
        PartitionAppendQueue(RecordingLog(InMemoryEventLog(PARTITIONS)), AppendQueueOptions())
            .apply { merging.assert().isFalse() }.close()
    }

    @Test
    fun `merging takes at least one committer and one partition's group a call`() {
        assertThrows<IllegalArgumentException> { AppendQueueOptions(mergers = 0) }
        assertThrows<IllegalArgumentException> { AppendQueueOptions(maxBatch = 64, mergeMax = 32) }
        AppendQueueOptions().mergers.assert().isEqualTo(2)
        AppendQueueOptions().mergeMax.assert().isEqualTo(512)
    }

    private open class RecordingLog(
        private val delegate: EventLog,
        override val appendsAcrossPartitions: Boolean = false,
    ) : EventLog by delegate {
        val batches = CopyOnWriteArrayList<List<EventStreamRecord>>()

        override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
            batches += streams.map { it.first }
            return delegate.appendAll(streams)
        }
    }

    /** Holds its first batch until [release]; batch number [failBatch] (from 1) throws. */
    private class GatedLog(
        delegate: EventLog,
        private val failBatch: Int? = null,
        acrossPartitions: Boolean = false,
    ) : RecordingLog(delegate, acrossPartitions) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
            entered.complete(Unit)
            release.await()
            if (batches.size + 1 == failBatch) {
                batches += streams.map { it.first }
                error("The batch failed.")
            }
            return super.appendAll(streams)
        }
    }

    private companion object {
        const val PARTITIONS = 4
        const val AGGREGATES = 256
        const val VERSIONS = 40
        const val NANOS_PER_SECOND = 1e9
    }
}
