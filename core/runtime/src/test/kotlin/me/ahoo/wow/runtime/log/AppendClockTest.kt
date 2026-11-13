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

import kotlinx.coroutines.Dispatchers
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
import me.ahoo.wow.spi.log.AppendCallMarks
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * [AppendQueueCounts.clock]: each call's time from its group taken to its committer free, and the steps a log that
 * marks them saw, only while the counts are read.
 */
class AppendClockTest {
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
    fun `a clocked call should be split into the steps its log marked, which sum to the call`(): Unit = runBlocking {
        val log = MarkingLog(InMemoryEventLog(PARTITIONS), appendsAcrossPartitions = true)
        val queue = PartitionAppendQueue(log)
        val clock = queue.counts().clock
        // One append at a time: each its own call.
        (0 until PARTITIONS).forEach { queue.append(stream(refIn(it))) }

        // A call is clocked once its committer is free, after its callers resumed: waited for.
        eventually(5.seconds) { clock.calls == PARTITIONS.toLong() }
        clock.marked.assert().isEqualTo(PARTITIONS.toLong())
        log.found.assert().containsOnly(true)
        // The log sleeps a millisecond in its statement: at least that, a call.
        clock.statement.assert().isGreaterThanOrEqualTo(PARTITIONS * 1_000L)
        // Each step is rounded down to its microsecond: their sum is the call's, less at most one a step.
        val steps = listOf(clock.enter, clock.hop, clock.acquire, clock.bind, clock.statement, clock.release, clock.hopBack)
        steps.forEach { it.assert().isNotNegative() }
        steps.sum().assert().isBetween(clock.call - steps.size, clock.call)
        clock.complete.assert().isNotNegative()
        queue.close()
    }

    @Test
    fun `a queue whose counts are not read should hand its log no marks`(): Unit = runBlocking {
        val log = MarkingLog(InMemoryEventLog(PARTITIONS), appendsAcrossPartitions = true)
        val queue = PartitionAppendQueue(log)
        queue.append(stream(refIn(0)))
        log.found.assert().containsExactly(false)
        queue.close()
    }

    @Test
    fun `a log that marks nothing should have its calls clocked whole and none marked`(): Unit = runBlocking {
        val unmerged = object : EventLog by InMemoryEventLog(PARTITIONS) {
            override val appendsAcrossPartitions = false
        }
        val queue = PartitionAppendQueue(unmerged)
        val clock = queue.counts().clock
        (0 until PARTITIONS).forEach { queue.append(stream(refIn(it))) }

        eventually(5.seconds) { clock.calls == PARTITIONS.toLong() }
        clock.marked.assert().isZero()
        listOf(clock.enter, clock.hop, clock.acquire, clock.bind, clock.statement, clock.release, clock.hopBack)
            .forEach { it.assert().isZero() }
        clock.call.assert().isNotNegative()
        queue.close()
    }

    @Test
    fun `a call that throws should be clocked to its failure`(): Unit = runBlocking {
        val failing = object : EventLog by InMemoryEventLog(PARTITIONS) {
            override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> = error("The store is down.")
        }
        val queue = PartitionAppendQueue(failing)
        val clock = queue.counts().clock
        runCatching { queue.append(stream(refIn(0))) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)

        eventually(5.seconds) { clock.calls == 1L }
        clock.marked.assert().isZero()
        queue.close()
    }

    /**
     * Marks each instant a store's log sees, its blocking work on a thread of its own as PostgreSQL's is, and a
     * millisecond's statement; [found] says, per call, whether the call carried marks.
     */
    private class MarkingLog(
        private val delegate: EventLog,
        override val appendsAcrossPartitions: Boolean,
    ) : EventLog by delegate {
        val found: MutableList<Boolean> = Collections.synchronizedList(ArrayList())

        override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
            val marks = currentCoroutineContext()[AppendCallMarks]?.apply { entered() }
            found += marks != null
            val outcomes = withContext(Dispatchers.IO) {
                marks?.onThread()
                marks?.acquired()
                marks?.sent()
                Thread.sleep(1)
                marks?.read()
                marks?.released()
                streams
            }
            return delegate.appendAll(outcomes)
        }
    }

    private companion object {
        const val PARTITIONS = 4
    }
}
