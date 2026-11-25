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
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Review r2 (wire formats): a record with a null value (a tombstone, which another producer on the topic or a
 * compaction-minded tool may write) reaches `SourceMessage.of(…, value: ByteArray)` as `record.value()`, null, in the
 * shared consumer's `deliver` (`SharedTopicConsumer.kt:317-322`). Kotlin's null check throws there, the consumer's loop
 * takes it as its own failure (`:192-194`, "The consumer of … failed; its tails read again.") and ends the session,
 * failing the tail of every partition the node reads of the topic; each read again polls the same record and ends
 * again. `operate/kafka.md` ("A topic may also hold a record Wow cannot read") says a record that is no envelope fails
 * the read of its partition, with its offset logged.
 */
class R2KafkaTombstoneTest {
    private val topic = "wow.shop.order.state"

    private fun record(offset: Long, value: ByteArray?, partition: Int) = ConsumerRecord<String, ByteArray>(
        topic,
        partition,
        offset,
        0L,
        TimestampType.CREATE_TIME,
        0,
        0,
        "shop.order/t/o-$partition",
        value,
        RecordHeaders(),
        Optional.empty(),
    )

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(10.seconds) {
            while (!condition()) {
                delay(1)
            }
        }
    }

    @Test
    fun `a record with no value should not stop the tails of the topic's other partitions`(): Unit = runBlocking {
        val zero = TopicPartition(topic, 0)
        val one = TopicPartition(topic, 1)
        val shared = MockConsumer<String, ByteArray>("earliest").apply {
            updateBeginningOffsets(mapOf(zero to 0L, one to 0L))
            updateEndOffsets(mapOf(zero to 0L, one to 0L))
        }
        val source = KafkaPartitionedSource({ shared }, pollTimeout = 1.milliseconds)
        val healthy = async(Dispatchers.Default) { runCatching { source.read(topic, 0, 0).take(1).toList() } }
        val withTombstone = async(Dispatchers.Default) { runCatching { source.read(topic, 1, 0).take(1).toList() } }
        eventually { shared.assignment() == setOf(zero, one) }
        shared.updateEndOffsets(mapOf(zero to 1L, one to 1L))
        shared.addRecord(record(0, null, partition = 1))
        shared.addRecord(record(0, "a0".toByteArray(), partition = 0))
        val read = withTimeout(10.seconds) { healthy.await() }
        read.exceptionOrNull().assert().describedAs("partition 0's tail").isNull()
        read.getOrThrow().map { String(it.value) }.assert().containsExactly("a0")
        withTombstone.cancel()
        source.close()
    }
}
