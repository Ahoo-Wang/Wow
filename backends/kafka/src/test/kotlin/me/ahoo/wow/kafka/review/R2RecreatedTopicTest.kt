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

package me.ahoo.wow.kafka.review

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kafka.KafkaPartitionedSource
import me.ahoo.wow.kafka.OffsetGapException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Round two, the upgrade path (r2-upgrade-notes.md finding 4): a state topic deleted and created again while its
 * subscribers keep their checkpoints, the offsets of the old topic. `OffsetGaps.resolve` (`OffsetGaps.kt:76-79`)
 * knows the partition's offsets alone: a checkpoint past the new partition's end is a gap ("the topic was created
 * again or truncated"), but one within it is taken as valid. `OffsetGapPolicy.FAIL`'s KDoc and Operate › Kafka
 * ("Offset gaps") say the read stays where it is, parked in effect, until an operator restarts it with
 * `wow.kafka.offset-gap=earliest`; a subscription retries its read with the same checkpoint meanwhile.
 */
class R2RecreatedTopicTest {
    private val topic = "wow.shop.order.state"
    private val zero = TopicPartition(topic, 0)

    private fun partition(start: Long, end: Long) = MockConsumer<String, ByteArray>("earliest").apply {
        updateBeginningOffsets(mapOf(zero to start))
        updateEndOffsets(mapOf(zero to end))
    }

    private fun record(offset: Long) = ConsumerRecord(
        topic, 0, offset, 0L, TimestampType.CREATE_TIME, 0, 0, "shop.order/t/o-$offset", "v$offset".toByteArray(),
        RecordHeaders(listOf(RecordHeader("wow-kind", "state-event".toByteArray()))), Optional.empty(),
    )

    private suspend fun readOne(source: KafkaPartitionedSource, mock: MockConsumer<String, ByteArray>, from: Long) =
        runCatching {
            withTimeout(10.seconds) {
                val reading = async(Dispatchers.Default) { source.read(topic, 0, from).take(1).toList() }
                while (mock.assignment().isEmpty()) delay(1)
                mock.addRecord(record(from))
                reading.await()
            }
        }

    @Test
    fun `a read refused at a recreated partition should stay refused once the new partition grows past its checkpoint`(): Unit =
        runBlocking {
            // The subscription's checkpoint is offset 10 of the old topic; the new one holds 0 to 3 so far.
            val shorter = partition(0, 3)
            // Then the publisher writes on: the new partition holds 0 to 15 when the subscription retries its read.
            val longer = partition(0, 15)
            val made = ArrayDeque(listOf(shorter, longer))
            val source = KafkaPartitionedSource({ made.removeFirst() }, 1.milliseconds)

            val first = runCatching { withTimeout(10.seconds) { source.read(topic, 0, 10).toList() } }
            (first.exceptionOrNull() as OffsetGapException).message.assert().contains("ends at 3, before offset 10")
            source.offsetGaps.assert().isEqualTo(1L)

            // The retry, from the same checkpoint: still the recreated topic, whose offsets 0 to 9 nobody read.
            val retried = readOne(source, longer, 10)
            retried.exceptionOrNull().assert().isInstanceOf(OffsetGapException::class.java)
            source.close()
        }

    /**
     * The design doubt, held as it is (this test passes on main): a subscriber that first reads after the new
     * partition already holds its checkpoint's offset reads on from there, and counts no gap. Nothing in an offset
     * says the topic is another one; a checkpoint that kept the topic's id (Kafka's topic ids, KIP-516) would.
     */
    @Test
    fun `a recreated partition already longer than the checkpoint is read on from the old offset, counting no gap`(): Unit =
        runBlocking {
            val longer = partition(0, 15)
            val source = KafkaPartitionedSource({ longer }, 1.milliseconds)

            val read = readOne(source, longer, 10)

            read.getOrThrow().map { it.offset }.assert().containsExactly(10L)
            source.offsetGaps.assert().isZero()
            source.close()
        }
}
