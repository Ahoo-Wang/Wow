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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * What a free committer of the [PartitionAppendQueue] takes of the lanes' groups handed to it: its share of the
 * streams handed and not taken (over the committers, rounded up), the oldest group first and whole, so the other
 * committers start from what it leaves; a lone committer takes it all.
 */
class PartitionAppendQueueMergeTest {
    private val type = AggregateType("bank", "account")

    @Test
    fun `a free committer of two takes its share of the streams handed, the oldest groups first`(): Unit =
        runBlocking {
            val log = HeldLog(InMemoryEventLog(PARTITIONS), held = 2)
            val options = AppendQueueOptions(maxBatch = 1, mergers = 2, mergeMax = 512, shareFloor = 0)
            val queue = PartitionAppendQueue(log, options)
            val counts = queue.counts()
            try {
                // Both committers held, each in its first call: partition 0's, then partition 1's.
                val held = (0..1).map { partition ->
                    append(queue, refIn(partition))
                        .also { eventually(5.seconds) { log.entered.get() == partition + 1 } }
                }
                // The other partitions' groups handed in turn, so their order in the channel is known.
                val rest = (2 until PARTITIONS).map { partition ->
                    append(queue, refIn(partition))
                        .also { eventually(5.seconds) { counts.handedGroups == partition - 1 } }
                }
                log.release(0)
                eventually(5.seconds) { log.calls.size == 4 }
                // Certain: the second committer is held in its call throughout, so the first alone takes, and no
                // append comes after the six groups: 6 streams waiting over two committers, then 3, then 1.
                log.calls.drop(1).map { call -> call.map { partitionOf(it) } }
                    .assert().containsExactly(listOf(2, 3, 4), listOf(5, 6), listOf(7))
                log.release(1)
                (held + rest).forEach { it.await().assert().isInstanceOf(AppendOutcome.Committed::class.java) }
            } finally {
                log.release(0)
                log.release(1)
                queue.close()
            }
        }

    @Test
    fun `a committer of two takes every group handed while no more than the floor waits`(): Unit = runBlocking {
        val log = HeldLog(InMemoryEventLog(PARTITIONS), held = 2)
        val queue = PartitionAppendQueue(log, AppendQueueOptions(maxBatch = 1, mergers = 2, mergeMax = 512))
        val counts = queue.counts()
        try {
            val held = (0..1).map { partition ->
                append(queue, refIn(partition)).also { eventually(5.seconds) { log.entered.get() == partition + 1 } }
            }
            val rest = (2 until PARTITIONS).map { partition ->
                append(queue, refIn(partition))
                    .also { eventually(5.seconds) { counts.handedGroups == partition - 1 } }
            }
            log.release(0)
            eventually(5.seconds) { log.calls.size == 2 }
            // Certain: the second committer held throughout; six streams waiting, under the default floor.
            (PARTITIONS - 2 <= AppendQueueOptions.DEFAULT_SHARE_FLOOR).assert().isTrue()
            log.calls[1].map { partitionOf(it) }.assert().containsExactly(2, 3, 4, 5, 6, 7)
            log.release(1)
            (held + rest).forEach { it.await().assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        } finally {
            log.release(0)
            log.release(1)
            queue.close()
        }
    }

    @Test
    fun `the share's floor is never negative`() {
        assertThrows<IllegalArgumentException> { AppendQueueOptions(shareFloor = -1) }
        AppendQueueOptions().shareFloor.assert().isEqualTo(AppendQueueOptions.DEFAULT_SHARE_FLOOR)
    }

    @Test
    fun `a lone committer takes every group handed`(): Unit = runBlocking {
        val log = HeldLog(InMemoryEventLog(PARTITIONS), held = 1)
        val queue = PartitionAppendQueue(log, AppendQueueOptions(maxBatch = 1, mergers = 1, mergeMax = 512))
        val counts = queue.counts()
        try {
            val first = append(queue, refIn(0))
            eventually(5.seconds) { log.entered.get() == 1 }
            val rest = (1 until PARTITIONS).map { partition ->
                append(queue, refIn(partition)).also { eventually(5.seconds) { counts.handedGroups == partition } }
            }
            log.release(0)
            (listOf(first) + rest).forEach { it.await().assert().isInstanceOf(AppendOutcome.Committed::class.java) }
            // Certain: one committer, held until all seven groups were handed: no one to leave a share to.
            log.calls.map { it.size }.assert().containsExactly(1, PARTITIONS - 1)
        } finally {
            log.release(0)
            queue.close()
        }
    }

    @Test
    fun `the oldest group is taken whole, whatever the share of what waits is`(): Unit = runBlocking {
        val log = HeldLog(InMemoryEventLog(PARTITIONS), held = 2)
        val options = AppendQueueOptions(maxBatch = 4, mergers = 2, mergeMax = 512, shareFloor = 0)
        val queue = PartitionAppendQueue(log, options)
        val refs = refsIn(1, 5)
        try {
            val first = append(queue, refs.first())
            eventually(5.seconds) { log.entered.get() == 1 }
            val other = append(queue, refIn(2))
            eventually(5.seconds) { log.entered.get() == 2 }
            // Queued behind the lane's group in the first held call: the lane takes them as one group of four next.
            val group = refs.drop(1).map { append(queue, it) }
            eventually(5.seconds) { queue.queued == group.size }
            log.release(0)
            eventually(5.seconds) { log.calls.size == 2 }
            // Certain: the second committer held throughout, and the lane's four appends queued before its first
            // group was done: the first committer's next call is that group, whole, though its share is two.
            log.calls[1].size.assert().isEqualTo(group.size)
            log.release(1)
            (listOf(first, other) + group).forEach {
                it.await().assert().isInstanceOf(AppendOutcome.Committed::class.java)
            }
        } finally {
            log.release(0)
            log.release(1)
            queue.close()
        }
    }

    private fun CoroutineScope.append(queue: PartitionAppendQueue, ref: AggregateRef): Deferred<AppendOutcome> =
        async(Dispatchers.Default) { queue.append(stream(ref)) }

    private fun refIn(partition: Int): AggregateRef = refsIn(partition, 1).single()

    private fun refsIn(partition: Int, count: Int): List<AggregateRef> = generateSequence(0) { it + 1 }
        .map { type.ref("tenant", "account-$it") }
        .filter { Partitioner.partitionOf(it, PARTITIONS) == partition }
        .take(count)
        .toList()

    private fun partitionOf(stream: EventStreamRecord) = Partitioner.partitionOf(stream.ref, PARTITIONS)

    private fun stream(ref: AggregateRef) = EventStreamRecord(
        ref = ref,
        version = 1,
        commandId = "command-${ref.id}",
        requestId = ref.id,
        commandType = "bank.Deposit",
        commitTime = 1L,
        events = listOf(EventRecord("bank.Deposited", 1, """{"amount":1}""")),
        scope = Scope.NONE,
    )

    /**
     * A log of several partitions a call that holds each of its first [held] calls until it is released by its number,
     * recording every call's streams once it is let through.
     */
    private class HeldLog(private val delegate: EventLog, held: Int) : EventLog by delegate {
        override val appendsAcrossPartitions: Boolean = true
        val entered = AtomicInteger()
        private val gates = List(held) { CompletableDeferred<Unit>() }
        val calls = CopyOnWriteArrayList<List<EventStreamRecord>>()

        fun release(call: Int) {
            gates[call].complete(Unit)
        }

        override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
            gates.getOrNull(entered.getAndIncrement())?.await()
            calls += streams.map { it.stream }
            return delegate.appendAll(streams)
        }
    }

    private companion object {
        const val PARTITIONS = 8
    }
}
