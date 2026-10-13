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
}
