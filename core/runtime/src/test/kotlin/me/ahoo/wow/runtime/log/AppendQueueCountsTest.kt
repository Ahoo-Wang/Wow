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
import kotlin.time.Duration.Companion.seconds

/**
 * [PartitionAppendQueue.counts]: the calls to the log, their streams and those at their cap, and the groups handed to
 * the committers and not yet taken, counted from the first read on.
 */
class AppendQueueCountsTest {
    private val type = AggregateType("bank", "account")

    private fun refIn(partition: Int): AggregateRef = generateSequence(0) { it + 1 }
        .map { type.ref("tenant", "account-$it") }
        .first { Partitioner.partitionOf(it, PARTITIONS) == partition }

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

    @Test
    fun `a merging queue should count its calls, their streams, the calls at their cap and the groups handed`(): Unit =
        runBlocking {
            val log = HeldLog(InMemoryEventLog(PARTITIONS), appendsAcrossPartitions = true)
            val queue = PartitionAppendQueue(log, AppendQueueOptions(maxBatch = 1, mergers = 1, mergeMax = 2))
            val counts = queue.counts()
            queue.counts().assert().isSameAs(counts)
            val held = async(Dispatchers.Default) { queue.append(stream(refIn(0))) }
            withTimeout(5.seconds) { log.entered.await() }
            // The one committer is in the first call: the other partitions' groups wait for it, handed.
            val others = (1 until PARTITIONS).map { async(Dispatchers.Default) { queue.append(stream(refIn(it))) } }
            eventually(5.seconds) { counts.handedGroups == PARTITIONS - 1 }
            counts.handedStreams.assert().isEqualTo(PARTITIONS - 1)

            log.release.complete(Unit)
            (listOf(held) + others).forEach { it.await().assert().isInstanceOf(AppendOutcome.Committed::class.java) }
            // One stream, then two at the cap of two, then the last one.
            counts.calls.assert().isEqualTo(3L)
            counts.streams.assert().isEqualTo(PARTITIONS.toLong())
            counts.full.assert().isEqualTo(1L)
            counts.handedGroups.assert().isZero()
            counts.handedStreams.assert().isZero()
            queue.close()
        }

    @Test
    fun `the counts should start at their first read, a group handed before it never counted taken`(): Unit =
        runBlocking {
            val log = HeldLog(InMemoryEventLog(PARTITIONS), appendsAcrossPartitions = true)
            val queue = PartitionAppendQueue(log, AppendQueueOptions(mergers = 1))
            val held = async(Dispatchers.Default) { queue.append(stream(refIn(0))) }
            withTimeout(5.seconds) { log.entered.await() }
            val others = (1 until PARTITIONS).map { async(Dispatchers.Default) { queue.append(stream(refIn(it))) } }
            eventually(5.seconds) { queue.queued == 0 }

            // Read only now: the call in flight and the groups handed meanwhile were before it.
            val counts = queue.counts()
            log.release.complete(Unit)
            (listOf(held) + others).forEach { it.await() }
            // The held call is not counted: only the others' streams, in one call or, were a lane slow to hand its
            // group, two.
            counts.streams.assert().isEqualTo((PARTITIONS - 1).toLong())
            counts.full.assert().isZero()
            counts.handedGroups.assert().isZero()
            counts.handedStreams.assert().isZero()
            queue.close()
        }

    @Test
    fun `a queue that does not merge should count each lane's batch, at its cap at maxBatch`(): Unit = runBlocking {
        val unmerged = object : EventLog by InMemoryEventLog(PARTITIONS) {
            override val appendsAcrossPartitions = false
        }
        val queue = PartitionAppendQueue(unmerged, AppendQueueOptions(maxBatch = 1))
        queue.merging.assert().isFalse()
        val counts = queue.counts()
        (0 until PARTITIONS).forEach { queue.append(stream(refIn(it))) }
        counts.calls.assert().isEqualTo(PARTITIONS.toLong())
        counts.streams.assert().isEqualTo(PARTITIONS.toLong())
        counts.full.assert().isEqualTo(PARTITIONS.toLong())
        counts.handedGroups.assert().isZero()
        queue.close()
    }

    /** Holds its first call until [release]. */
    private class HeldLog(
        private val delegate: EventLog,
        override val appendsAcrossPartitions: Boolean,
    ) : EventLog by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
            entered.complete(Unit)
            release.await()
            return delegate.appendAll(streams)
        }
    }

    private companion object {
        const val PARTITIONS = 4
    }
}
