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

package me.ahoo.wow.mongo.log

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Deep review r1 (MongoDB backend): a log of 4,096 partitions, the most its positions hold (12 bits of partition, 51
 * of sequence). The reads bound a partition's positions above by `positionId(partition + 1, 0)`, which for partition
 * 4095 is `1 shl 63`: `Long.MIN_VALUE`. Every `_id < Long.MIN_VALUE` matches nothing, so the head of partition 4095
 * is always null, and a hole longer than the tail's window there is never seen to have anything past it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoTopPartitionTest : MongoLogFixtures() {
    private val partitions = Partitioner.SLOTS
    private val logContext = MongoLogFixture.newContext("top")
    private lateinit var first: MongoEventLog
    private val order = AggregateType(logContext, "order")

    @BeforeAll
    fun createSchema() {
        first = MongoLogFixture.log(partitions, logContext, aggregates = listOf(order.name), tailBatch = TAIL_BATCH)
    }

    /** A node of the same log: its own blocks. */
    private fun anotherNode() = MongoEventLog(
        MongoLogFixture.client,
        MongoLogFixture.DATABASE,
        logContext,
        partitions,
        options = MongoEventLogOptions(tailBatch = TAIL_BATCH),
    )

    /** The id of an aggregate of [order] in [partition], the [nth] such. */
    private fun idIn(partition: Int, nth: Int = 0): String = generateSequence(0) { it + 1 }
        .map { "a-$it" }
        .filter { Partitioner.partitionOf(order.ref("tenant", it), partitions) == partition }
        .drop(nth)
        .first()

    @Test
    fun `the head of the last partition is the sequence of its stream`(): Unit = runBlocking {
        val top = partitions - 1
        val committed = first.append(stream(idIn(top), 1, aggregate = order))
        committed.assert().isInstanceOf(AppendOutcome.Committed::class.java)
        val position = (committed as AppendOutcome.Committed).position
        position.partition.assert().isEqualTo(top)

        first.head(top).assert().isEqualTo(position.sequence)
        first.heads(listOf(top)).assert().isEqualTo(mapOf(top to position.sequence))
    }

    @Test
    fun `a tail of the last partition passes a stopped node's block longer than its window`(): Unit = runBlocking {
        tailPassesStoppedBlock(partitions - 1)
    }

    @Test
    fun `a tail of the partition below it passes a stopped node's block longer than its window`(): Unit =
        runBlocking {
            // The same flow on partition 4094, whose upper bound does not overflow: it passes (the control).
            tailPassesStoppedBlock(partitions - 2)
        }

    /**
     * A node appends one stream in [partition] from its block of 256 and stops without handing off; another node
     * appends after it from its own block. A tail with a window of [TAIL_BATCH] sequences meets the stopped block's
     * 255 unused sequences, finds a stream past them, seals the block after the stall grace and delivers both.
     */
    private suspend fun tailPassesStoppedBlock(partition: Int) {
        val stopped = anotherNode()
        stopped.append(stream(idIn(partition, 1), 1, aggregate = order))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)
        val live = anotherNode()
        live.append(stream(idIn(partition, 2), 1, aggregate = order))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)

        // Another test's stream may share the partition: the tail is read for this test's two.
        val ids = setOf(idIn(partition, 1), idIn(partition, 2))
        val entries =
            withTimeout(TAIL_WAIT) { live.tail(partition).filter { it.stream.ref.id in ids }.take(2).toList() }

        entries.map { it.stream.ref.id }.assert().containsExactly(idIn(partition, 1), idIn(partition, 2))
    }

    private companion object {
        const val TAIL_BATCH = 10
        const val TAIL_WAIT = 15_000L
    }
}
