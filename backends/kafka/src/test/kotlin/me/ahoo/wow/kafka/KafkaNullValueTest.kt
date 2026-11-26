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

package me.ahoo.wow.kafka

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
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
 * A record of no value, a tombstone another producer on the topic or a tool wrote (#7048): read as a message of no
 * bytes, which is no Wow envelope, so the read of its own partition fails naming it, while the topic's other partitions
 * read on. Read as Kafka's null, it failed the consumer every partition of the topic shares.
 */
class KafkaNullValueTest {
    private val topic = "wow.shop.order.state"
    private val zero = TopicPartition(topic, 0)
    private val one = TopicPartition(topic, 1)

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

    private fun consumer() = MockConsumer<String, ByteArray>("earliest").apply {
        updateBeginningOffsets(mapOf(zero to 0L, one to 0L))
        updateEndOffsets(mapOf(zero to 0L, one to 0L))
    }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(10.seconds) {
            while (!condition()) {
                delay(1)
            }
        }
    }

    @Test
    fun `the tails of a topic should read a record of no value as no bytes, the other partitions reading on`(): Unit =
        runBlocking {
            val shared = consumer()
            val source = KafkaPartitionedSource({ shared }, pollTimeout = 1.milliseconds)
            val healthy = async(Dispatchers.Default) { source.read(topic, 0, 0).take(1).toList() }
            val tombstoned = async(Dispatchers.Default) { source.read(topic, 1, 0).take(1).toList() }
            eventually { shared.assignment() == setOf(zero, one) }
            shared.updateEndOffsets(mapOf(zero to 1L, one to 1L))
            shared.addRecord(record(0, null, partition = 1))
            shared.addRecord(record(0, "a0".toByteArray(), partition = 0))
            withTimeout(10.seconds) {
                healthy.await().map { String(it.value) }.assert().containsExactly("a0")
                tombstoned.await().single().let {
                    it.offset.assert().isEqualTo(0L)
                    it.value.assert().isEmpty()
                }
            }
            source.close()
        }

    @Test
    fun `a bounded read and a read at offsets should read a record of no value as no bytes`(): Unit = runBlocking {
        val reading = consumer().apply {
            updateEndOffsets(mapOf(zero to 1L))
            schedulePollTask { addRecord(record(0, null, partition = 0)) }
        }
        val reader = KafkaPartitionedSource({ reading }, pollTimeout = 1.milliseconds)
        withTimeout(10.seconds) { reader.read(topic, 0, 0, 1).toList() }.single().value.assert().isEmpty()
        reader.close()

        val seeking = consumer().apply {
            updateEndOffsets(mapOf(zero to 1L))
            schedulePollTask { addRecord(record(0, null, partition = 0)) }
        }
        val readAt = KafkaPartitionedSource({ seeking }, pollTimeout = 1.milliseconds)
        withTimeout(10.seconds) { readAt.readAt(topic, 0, listOf(0L)) }.getValue(0L).value.assert().isEmpty()
        readAt.close()
    }
}
