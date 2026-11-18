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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import me.ahoo.wow.spi.log.ExecutionLease
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The lease mark of an append's command reaching the log through the queue, whose lanes and committers call the log
 * from coroutines of their own: the appends of commands run without the lease go in a call of their own under it.
 */
class PartitionAppendQueueLeaseTest {
    private val type = AggregateType("bank", "account")

    private fun refs(count: Int, partition: Int): List<AggregateRef> = generateSequence(0) { it + 1 }
        .map { type.ref("tenant", "account-$partition-$it") }
        .filter { Partitioner.partitionOf(it, PARTITIONS) == partition }
        .take(count)
        .toList()

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
    fun `a lane's batch commits the appends without the lease in a call of their own, after the others`(): Unit =
        runBlocking {
            val log = LeaseLog(InMemoryEventLog(PARTITIONS), acrossPartitions = false)
            val queue = PartitionAppendQueue(log)
            val (first, leased, degraded) = refs(5, 0).let { Triple(it[0], it.subList(1, 3), it.subList(3, 5)) }
            calls(queue, log, first, leased, degraded) { queue.queued == leased.size + degraded.size }
            // Three calls: the first held in the log until the four others queued behind it, then one batch of them,
            // split in two. Within each, the order is the callers' race.
            log.calls.map { it.first.toSet() }.assert().containsExactly(setOf(first), leased.toSet(), degraded.toSet())
            log.calls.map { it.second }.assert().containsExactly(null, null, ExecutionLease.NOT_HELD)
            queue.close()
        }

    @Test
    fun `a merged call splits the same way, whichever partitions its batches are of`(): Unit = runBlocking {
        val log = LeaseLog(InMemoryEventLog(PARTITIONS), acrossPartitions = true)
        val queue = PartitionAppendQueue(log, AppendQueueOptions(mergers = 1))
        val counts = queue.counts()
        val first = refs(1, 0).single()
        val leased = refs(1, 1)
        val degraded = refs(1, 2) + refs(1, 3)
        // Each lane hands its batch to the one committer, which holds the first.
        calls(queue, log, first, leased, degraded) { counts.handedStreams == leased.size + degraded.size }
        // Three calls: the first held until the three lanes handed theirs, which the lone committer takes at once.
        log.calls.map { it.first.toSet() }.assert().containsExactly(setOf(first), leased.toSet(), degraded.toSet())
        log.calls.map { it.second }.assert().containsExactly(null, null, ExecutionLease.NOT_HELD)
        queue.close()
    }

    @Test
    fun `a batch of appends without the lease alone is one call under it`(): Unit = runBlocking {
        val log = LeaseLog(InMemoryEventLog(PARTITIONS), acrossPartitions = false)
        val queue = PartitionAppendQueue(log)
        log.release.complete(Unit)
        val ref = refs(1, 0).single()
        withContext(ExecutionLease.NOT_HELD) { queue.append(stream(ref)) }.assert()
            .isInstanceOf(AppendOutcome.Committed::class.java)
        log.calls.assert().containsExactly(listOf(ref) to ExecutionLease.NOT_HELD)
        queue.close()
    }

    /**
     * [first] held in the log while [leased] and [degraded] wait behind it until [waiting], the latter without the
     * lease; then all of them committed.
     */
    private suspend fun calls(
        queue: PartitionAppendQueue,
        log: LeaseLog,
        first: AggregateRef,
        leased: List<AggregateRef>,
        degraded: List<AggregateRef>,
        waiting: () -> Boolean,
    ) = coroutineScope {
        val held = async(Dispatchers.Default) { queue.append(stream(first)) }
        log.entered.await()
        val rest = leased.map { async(Dispatchers.Default) { queue.append(stream(it)) } } +
            degraded.map { async(Dispatchers.Default + ExecutionLease.NOT_HELD) { queue.append(stream(it)) } }
        eventually { waiting() }
        log.release.complete(Unit)
        (listOf(held) + rest).awaitAll().forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
    }

    /** Records each call's streams and the lease mark its coroutine carries; holds the first call until [release]. */
    private class LeaseLog(
        private val delegate: EventLog,
        private val acrossPartitions: Boolean,
    ) : EventLog by delegate {
        val calls = CopyOnWriteArrayList<Pair<List<AggregateRef>, ExecutionLease?>>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override val appendsAcrossPartitions: Boolean get() = acrossPartitions

        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
            appendAll(listOf(Append.of(stream, transport))).single()

        override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
            calls += streams.map { it.stream.ref } to currentCoroutineContext()[ExecutionLease]
            entered.complete(Unit)
            release.await()
            return delegate.appendAll(streams)
        }
    }

    private companion object {
        const val PARTITIONS = 4
    }
}
