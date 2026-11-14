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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * What a free committer of the [PartitionAppendQueue] takes of the lanes' groups handed to it: half of the streams
 * handed and not taken, the oldest group first and whole, so the next committer starts from what it leaves.
 */
class PartitionAppendQueueMergeTest {
    private val type = AggregateType("bank", "account")

    @Test
    fun `a free committer takes half of the streams handed, the oldest groups first`(): Unit = runBlocking {
        val log = HeldLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions(maxBatch = 1, mergers = 1, mergeMax = 512))
        val counts = queue.counts()
        try {
            val first = async(Dispatchers.Default) { queue.append(stream(refIn(0))) }
            withTimeout(5.seconds) { log.entered.await() }
            // Each partition's group handed in turn, so their order in the channel is known.
            val rest = (1 until PARTITIONS).map { partition ->
                async(Dispatchers.Default) { queue.append(stream(refIn(partition))) }
                    .also { eventually(5.seconds) { counts.handedGroups == partition } }
            }
            log.release.complete(Unit)
            (listOf(first) + rest).forEach { it.await().assert().isInstanceOf(AppendOutcome.Committed::class.java) }
            // Certain: one committer, held in its first call until all seven groups were handed, and no append after
            // them, so each take sees what the last left: 7 streams waiting, then 3, then 1.
            log.calls.map { call -> call.map { partitionOf(it) } }
                .assert().containsExactly(listOf(0), listOf(1, 2, 3, 4), listOf(5, 6), listOf(7))
        } finally {
            queue.close()
        }
    }

    @Test
    fun `the oldest group is taken whole, whatever half of what waits is`(): Unit = runBlocking {
        val log = HeldLog(InMemoryEventLog(PARTITIONS))
        val queue = PartitionAppendQueue(log, AppendQueueOptions(maxBatch = 4, mergers = 1, mergeMax = 512))
        val refs = refsIn(1, 5)
        try {
            val first = async(Dispatchers.Default) { queue.append(stream(refs.first())) }
            withTimeout(5.seconds) { log.entered.await() }
            // Queued behind the lane's group in the held call: the lane takes them as one group of four next.
            val group = refs.drop(1).map { async(Dispatchers.Default) { queue.append(stream(it)) } }
            eventually(5.seconds) { queue.queued == group.size }
            log.release.complete(Unit)
            (listOf(first) + group).forEach { it.await().assert().isInstanceOf(AppendOutcome.Committed::class.java) }
            // Certain: one committer and one lane, its four appends queued before its first group was done.
            log.calls.map { it.size }.assert().containsExactly(1, 4)
        } finally {
            queue.close()
        }
    }

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

    /** A log of several partitions a call that holds its first call until [release], recording every call's streams. */
    private class HeldLog(private val delegate: EventLog) : EventLog by delegate {
        override val appendsAcrossPartitions: Boolean = true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<List<EventStreamRecord>>()

        override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
            entered.complete(Unit)
            release.await()
            calls += streams.map { it.stream }
            return delegate.appendAll(streams)
        }
    }

    private companion object {
        const val PARTITIONS = 8
    }
}
