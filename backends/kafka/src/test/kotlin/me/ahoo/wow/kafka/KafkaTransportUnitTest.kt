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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.TopicMessage
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The Kafka transport over `kafka-clients`' mock producer and consumer; the broker's own is in the integration tests. */
class KafkaTransportUnitTest {
    private val topic = "wow.shop.order.state"

    @Test
    fun `the producer should be idempotent and acknowledged by all replicas, whatever the properties say`() {
        val options = KafkaOptions(
            "broker:9092",
            mapOf("acks" to "1", "enable.idempotence" to "false", "client.id" to "shop", "group.id" to "ignored"),
        )
        val producer = options.producerConfig()
        producer[ProducerConfig.ACKS_CONFIG].assert().isEqualTo("all")
        producer[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG].assert().isEqualTo(true)
        producer["client.id"].assert().isEqualTo("shop")
        producer["bootstrap.servers"].assert().isEqualTo("broker:9092")
        val consumer = options.consumerConfig()
        consumer.containsKey(ConsumerConfig.GROUP_ID_CONFIG).assert().isFalse()
        consumer[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG].assert().isEqualTo(false)
        consumer[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG].assert().isEqualTo("earliest")
        consumer["client.id"].assert().isEqualTo("shop")
        assertThrows<IllegalArgumentException> { KafkaOptions(" ") }
        assertThrows<IllegalArgumentException> { KafkaOptions("broker:9092", pollTimeout = 0.milliseconds) }
    }

    @Test
    fun `a send should return once every message is acknowledged, keyed and with its headers`(): Unit = runBlocking {
        val mock = MockProducer(true, null, StringSerializer(), ByteArraySerializer())
        val producer = KafkaTopicProducer(mock)
        producer.send(topic, emptyList())
        producer.send(
            topic,
            listOf(
                TopicMessage("shop.order/t/o-1", "v1".toByteArray(), mapOf("wow-kind" to "state-event")),
                TopicMessage("shop.order/t/o-2", "v2".toByteArray()),
            ),
        )
        val sent = mock.history()
        sent.map { it.key() }.assert().containsExactly("shop.order/t/o-1", "shop.order/t/o-2")
        sent.map { String(it.value()) }.assert().containsExactly("v1", "v2")
        KafkaHeaders.of(sent[0].headers()).assert().isEqualTo(mapOf("wow-kind" to "state-event"))
        sent.map { it.topic() }.toSet().assert().containsExactly(topic)
        producer.close()
        mock.closed().assert().isTrue()
    }

    @Test
    fun `a send should fail when the broker does not acknowledge`(): Unit = runBlocking {
        val mock = MockProducer(false, null, StringSerializer(), ByteArraySerializer())
        val producer = KafkaTopicProducer(mock)
        val sending = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runCatching { producer.send(topic, TopicMessage("k", byteArrayOf(1))) }
        }
        withTimeout(10.seconds) {
            while (mock.history().isEmpty()) {
                delay(1)
            }
        }
        sending.isCompleted.assert().isFalse()
        mock.errorNext(IllegalStateException("not acknowledged")).assert().isTrue()
        sending.await().exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
    }

    private fun record(offset: Long, value: String, partition: Int = 0) = ConsumerRecord(
        topic,
        partition,
        offset,
        0L,
        TimestampType.CREATE_TIME,
        0,
        0,
        "shop.order/t/o-1",
        value.toByteArray(),
        RecordHeaders(listOf(RecordHeader("wow-kind", "state-event".toByteArray()), RecordHeader("empty", null))),
        Optional.empty(),
    )

    @Test
    fun `a source should list partitions and offsets through one consumer`(): Unit = runBlocking {
        val mock = MockConsumer<String, ByteArray>("earliest")
        var made = 0
        val source = KafkaPartitionedSource({ mock.also { made++ } })
        mock.updatePartitions(topic, listOf(1, 0).map { PartitionInfo(topic, it, null, null, null) })
        mock.updateBeginningOffsets(mapOf(TopicPartition(topic, 0) to 2L))
        mock.updateEndOffsets(mapOf(TopicPartition(topic, 0) to 5L))
        source.partitions(topic).assert().containsExactly(0, 1)
        source.partitions("unknown").assert().isEmpty()
        source.offsets(topic, 0).assert().isEqualTo(PartitionOffsets(2, 5))
        made.assert().isEqualTo(1)
        source.close()
        mock.closed().assert().isTrue()
    }

    @Test
    fun `a read should emit the partition's messages from the offset given and close its consumer`(): Unit =
        runBlocking {
            val mock = MockConsumer<String, ByteArray>("earliest")
            val partition = TopicPartition(topic, 0)
            mock.updateBeginningOffsets(mapOf(partition to 0L))
            mock.updateEndOffsets(mapOf(partition to 2L))
            // Past the end: the read seeks to the end and skips what lies before the offset asked for.
            mock.schedulePollTask { (2L..4L).forEach { mock.addRecord(record(it, "v$it")) } }
            mock.schedulePollTask { (5L..6L).forEach { mock.addRecord(record(it, "v$it")) } }
            val source = KafkaPartitionedSource({ mock }, pollTimeout = 1.milliseconds)
            val read = withTimeout(10.seconds) { source.read(topic, 0, 4).take(3).toList() }
            read.map { it.offset }.assert().containsExactly(4L, 5L, 6L)
            read.map { String(it.value) }.assert().containsExactly("v4", "v5", "v6")
            read[0].key.assert().isEqualTo("shop.order/t/o-1")
            read[0].headers.assert().isEqualTo(mapOf("wow-kind" to "state-event"))
            mock.closed().assert().isTrue()
            assertThrows<IllegalArgumentException> { runBlocking { source.read(topic, 0, -1).toList() } }
        }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(10.seconds) {
            while (!condition()) {
                delay(1)
            }
        }
    }

    @Test
    fun `the tails of a topic should share one consumer, and a second read of a partition have its own`(): Unit =
        runBlocking {
            val shared = MockConsumer<String, ByteArray>("earliest")
            val own = MockConsumer<String, ByteArray>("earliest")
            val zero = TopicPartition(topic, 0)
            val one = TopicPartition(topic, 1)
            shared.updateEndOffsets(mapOf(zero to 0L, one to 0L))
            own.updateEndOffsets(mapOf(zero to 0L))
            val made = ArrayDeque(listOf(shared, own))
            val source = KafkaPartitionedSource({ made.removeFirst() }, pollTimeout = 1.milliseconds)
            val first = async(Dispatchers.Default) { source.read(topic, 0, 0).take(2).toList() }
            eventually { shared.assignment() == setOf(zero) }
            // Already joined: its own consumer, from its own offset.
            val again = async(Dispatchers.Default) { source.read(topic, 0, 5).take(1).toList() }
            eventually { own.assignment() == setOf(zero) }
            val second = async(Dispatchers.Default) { source.read(topic, 1, 0).take(1).toList() }
            eventually { shared.assignment() == setOf(zero, one) }
            shared.addRecord(record(0, "a0"))
            shared.addRecord(record(1, "a1"))
            shared.addRecord(record(0, "b0", partition = 1))
            own.addRecord(record(5, "c5"))
            withTimeout(10.seconds) {
                first.await().map { String(it.value) }.assert().containsExactly("a0", "a1")
                second.await().map { String(it.value) }.assert().containsExactly("b0")
                again.await().map { it.offset }.assert().containsExactly(5L)
            }
            made.assert().isEmpty()
            // The last tail to leave closes the shared consumer.
            shared.closed().assert().isTrue()
            own.closed().assert().isTrue()
            source.close()
        }

    @Test
    fun `a full channel should pause its partition until it has room, and a failing consumer fail its tails`(): Unit =
        runBlocking {
            val mock = MockConsumer<String, ByteArray>("earliest")
            val zero = TopicPartition(topic, 0)
            mock.updateEndOffsets(mapOf(zero to 0L))
            val source = KafkaPartitionedSource({ mock }, pollTimeout = 1.milliseconds)
            val gate = CompletableDeferred<Unit>()
            val reading = async(Dispatchers.Default) {
                runCatching { source.read(topic, 0, 0).collect { gate.await() } }.exceptionOrNull()
            }
            eventually { mock.assignment() == setOf(zero) }
            // More than a channel holds, to a reader that does not take: the rest waits, the partition paused.
            (0L until 600L).forEach { mock.addRecord(record(it, "v$it")) }
            eventually { mock.paused() == setOf(zero) }
            mock.setPollException(org.apache.kafka.common.KafkaException("broker gone"))
            eventually { mock.closed() }
            gate.complete(Unit)
            reading.await().assert().isInstanceOf(org.apache.kafka.common.KafkaException::class.java)
            eventually { mock.closed() }
            source.close()
        }

    @Test
    fun `a bounded read should complete once its consumer passed the bound, and a key map to its partition`(): Unit =
        runBlocking {
            val mock = MockConsumer<String, ByteArray>("earliest")
            val zero = TopicPartition(topic, 0)
            mock.updateEndOffsets(mapOf(zero to 3L))
            mock.schedulePollTask { (0L..2L).forEach { mock.addRecord(record(it, "v$it")) } }
            val metadata = MockConsumer<String, ByteArray>("earliest")
            metadata.updatePartitions(topic, listOf(0, 1, 2).map { PartitionInfo(topic, it, null, null, null) })
            val made = ArrayDeque(listOf(mock, metadata))
            val source = KafkaPartitionedSource({ made.removeFirst() }, pollTimeout = 1.milliseconds)
            withTimeout(10.seconds) { source.read(topic, 0, 1, 3).toList() }.map { it.offset }
                .assert().containsExactly(1L, 2L)
            source.read(topic, 0, 3, 3).toList().assert().isEmpty()
            mock.closed().assert().isTrue()
            val key = "shop.order/t/o-1"
            source.partitionOf(topic, key).assert().isEqualTo(
                org.apache.kafka.common.utils.Utils.toPositive(
                    org.apache.kafka.common.utils.Utils.murmur2(key.toByteArray()),
                ) % 3,
            )
            assertThrows<IllegalStateException> { runBlocking { source.partitionOf("unknown", key) } }
            source.close()
        }
}
