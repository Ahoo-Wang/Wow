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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.TopicMessage
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import kotlin.concurrent.thread
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
        consumer[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG].assert().isEqualTo("none")
        consumer[ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG].assert().isEqualTo(false)
        consumer[ConsumerConfig.METADATA_MAX_AGE_CONFIG].assert().isEqualTo(30_000L)
        KafkaOptions("broker:9092", mapOf("metadata.max.age.ms" to "1000")).consumerConfig()["metadata.max.age.ms"]
            .assert().isEqualTo("1000")
        assertThrows<IllegalArgumentException> { KafkaOptions("broker:9092", metadataMaxAge = 0.seconds) }
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
            mock.updateEndOffsets(mapOf(partition to 4L))
            // At the end: the read waits for the messages to reach it.
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
            shared.updateBeginningOffsets(mapOf(zero to 0L, one to 0L))
            own.updateEndOffsets(mapOf(zero to 5L))
            own.updateBeginningOffsets(mapOf(zero to 0L))
            val made = ArrayDeque(listOf(shared, own))
            val source = KafkaPartitionedSource({ made.removeFirst() }, pollTimeout = 1.milliseconds)
            val first = async(Dispatchers.Default) { source.read(topic, 0, 0).take(2).toList() }
            eventually { shared.assignment() == setOf(zero) }
            // Already joined: its own consumer, from its own offset.
            val again = async(Dispatchers.Default) { source.read(topic, 0, 5).take(1).toList() }
            eventually { own.assignment() == setOf(zero) }
            val second = async(Dispatchers.Default) { source.read(topic, 1, 0).take(1).toList() }
            eventually { shared.assignment() == setOf(zero, one) }
            // A MockConsumer's end offsets do not follow its records: move them first, as a broker's do. Left behind,
            // they put a reader that has read its records past its partition's end, and when the other reader's leave
            // places it again the shared consumer fails it with an offset gap: its leave then returns at once, before
            // the session closes the consumer (the flaky `closed()` below).
            shared.updateEndOffsets(mapOf(zero to 2L, one to 1L))
            own.updateEndOffsets(mapOf(zero to 6L))
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
            // No reader met a gap: each one left by its own leave, the last of which closes the shared consumer.
            source.offsetGaps.assert().isZero()
            shared.closed().assert().isTrue()
            own.closed().assert().isTrue()
            source.close()
        }

    @Test
    fun `a partition joining while the last one leaves should get a consumer of its own, and the leave return`(): Unit =
        runBlocking {
            // Finding C.1 F1: the last partition left, then another joined before the polling coroutine saw it was
            // the last; the coroutine kept polling for the new one, and the leave, waiting for it to stop, never
            // returned: the lost partition's subscriptions never stopped, and its node never took a partition again.
            val stopping = MockConsumer<String, ByteArray>("earliest")
            val next = MockConsumer<String, ByteArray>("earliest")
            val zero = TopicPartition(topic, 0)
            stopping.updateEndOffsets(mapOf(zero to 0L, TopicPartition(topic, 1) to 0L))
            stopping.updateBeginningOffsets(mapOf(zero to 0L, TopicPartition(topic, 1) to 0L))
            next.updateEndOffsets(mapOf(zero to 0L))
            next.updateBeginningOffsets(mapOf(zero to 0L))
            val made = ArrayDeque(listOf(stopping, next))
            // The first coroutine makes its consumer only once the leave and the join below are done. It is the first
            // to make one because the test waits for it to be making it before the leave: the second coroutine, started
            // by the join, could otherwise make its consumer first and take `stopping`.
            val entered = CountDownLatch(1)
            val making = CountDownLatch(1)
            val shared = SharedTopicConsumer(
                topic,
                {
                    val consumer = synchronized(made) { made.removeFirst() }
                    if (consumer === stopping) {
                        entered.countDown()
                        making.await()
                    }
                    consumer
                },
                1.milliseconds,
                Dispatchers.IO,
            )
            val last = checkNotNull(shared.join(1, 0))
            withTimeout(10.seconds) { runInterruptible(Dispatchers.IO) { entered.await() } }
            // Runs up to its wait for the coroutine to stop, a wait it cannot be cancelled out of.
            val leaving = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.UNDISPATCHED) {
                shared.leave(last)
            }
            val joined = checkNotNull(shared.join(0, 0))
            making.countDown()

            withTimeout(10.seconds) { leaving.await() }
            stopping.closed().assert().isTrue()
            eventually { next.assignment() == setOf(zero) }
            next.addRecord(record(0, "a0"))
            withTimeout(10.seconds) { joined.channel.receive() }.offset.assert().isEqualTo(0L)
            shared.leave(joined)
            next.closed().assert().isTrue()
            // Leaving twice does nothing.
            shared.leave(joined)
            shared.close()
        }

    @Test
    fun `a partition joining as the last one leaves should get its own consumer, whichever session runs first`(): Unit =
        runBlocking {
            // The other order of the test above: the coroutine of the session the join starts makes its consumer before
            // the coroutine of the session the leave ends has run at all. Each session closes the consumer it made.
            val first = consumerWith(0, 0, 0, 1)
            val second = consumerWith(0, 0, 0, 1)
            val made = ArrayDeque(listOf(first, second))
            // Holds each session's coroutine, so the test starts them in the order it chooses.
            val held = ArrayList<Runnable>()
            val dispatcher = Executor { task -> synchronized(held) { held += task } }.asCoroutineDispatcher()
            val consumers = { synchronized(made) { made.removeFirst() } }
            val shared = SharedTopicConsumer(topic, consumers, 1.milliseconds, dispatcher)
            val last = checkNotNull(shared.join(1, 0))
            val leaving = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.UNDISPATCHED) {
                shared.leave(last)
            }
            val joined = checkNotNull(shared.join(0, 0))
            val (stopped, started) = synchronized(held) { held.toList() }.also { it.size.assert().isEqualTo(2) }
            thread { started.run() }
            eventually { synchronized(made) { made.size == 1 } }
            thread { stopped.run() }

            withTimeout(10.seconds) { leaving.await() }
            second.closed().assert().isTrue()
            first.closed().assert().isFalse()
            eventually { first.assignment() == setOf(TopicPartition(topic, 0)) }
            first.addRecord(record(0, "a0"))
            withTimeout(10.seconds) { joined.channel.receive() }.offset.assert().isEqualTo(0L)
            shared.leave(joined)
            first.closed().assert().isTrue()
            synchronized(held) { held.size }.assert().isEqualTo(2)
            shared.close()
        }

    @Test
    fun `a full channel should pause its partition until it has room, and a failing consumer fail its tails`(): Unit =
        runBlocking {
            val mock = MockConsumer<String, ByteArray>("earliest")
            val zero = TopicPartition(topic, 0)
            mock.updateEndOffsets(mapOf(zero to 0L))
            mock.updateBeginningOffsets(mapOf(zero to 0L))
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
    fun `a consumer that cannot be made should fail the tails, and the next read get a new one`(): Unit = runBlocking {
        // Finding A2-3: the bootstrap address did not resolve; the session died and its readers waited forever.
        val mock = MockConsumer<String, ByteArray>("earliest")
        val zero = TopicPartition(topic, 0)
        mock.updateEndOffsets(mapOf(zero to 0L))
        mock.updateBeginningOffsets(mapOf(zero to 0L))
        var attempts = 0
        val source = KafkaPartitionedSource(
            {
                attempts++
                if (attempts == 1) throw org.apache.kafka.common.KafkaException("Failed to construct kafka consumer")
                mock
            },
            pollTimeout = 1.milliseconds,
        )
        val failed = withTimeout(10.seconds) { runCatching { source.read(topic, 0, 0).toList() }.exceptionOrNull() }
        failed.assert().isInstanceOf(org.apache.kafka.common.KafkaException::class.java)
        val reading = async(Dispatchers.Default) { source.read(topic, 0, 0).take(1).toList() }
        eventually { mock.assignment() == setOf(zero) }
        mock.addRecord(record(0, "v0"))
        withTimeout(10.seconds) { reading.await() }.map { it.offset }.assert().containsExactly(0L)
        attempts.assert().isEqualTo(2)
        source.close()
    }

    private fun consumerWith(start: Long, end: Long, vararg partitions: Int) =
        MockConsumer<String, ByteArray>("earliest").apply {
            updateBeginningOffsets(partitions.associate { TopicPartition(topic, it) to start })
            updateEndOffsets(partitions.associate { TopicPartition(topic, it) to end })
        }

    @Test
    fun `a tail from an offset no longer retained should fail by default, and read from the earliest if asked`(): Unit =
        runBlocking {
            // Finding A2-11: the consumer reset to the earliest offset on its own and skipped what was deleted.
            val failing = KafkaPartitionedSource({ consumerWith(5, 8, 0) }, 1.milliseconds)
            val gap = runCatching { withTimeout(10.seconds) { failing.read(topic, 0, 2).toList() } }.exceptionOrNull()
            (gap as OffsetGapException).offset.assert().isEqualTo(2L)
            gap.start.assert().isEqualTo(5L)
            gap.message.assert().contains("no longer retains offset 2")
            failing.offsetGaps.assert().isEqualTo(1L)
            failing.offsetGap.assert().isEqualTo(OffsetGapPolicy.FAIL)
            failing.close()

            val mock = consumerWith(5, 8, 0)
            val earliest = KafkaPartitionedSource({ mock }, 1.milliseconds, Dispatchers.IO, OffsetGapPolicy.EARLIEST)
            val reading = async(Dispatchers.Default) { earliest.read(topic, 0, 2).take(2).toList() }
            eventually { mock.assignment().isNotEmpty() }
            mock.addRecord(record(5, "v5"))
            mock.addRecord(record(6, "v6"))
            withTimeout(10.seconds) { reading.await() }.map { it.offset }.assert().containsExactly(5L, 6L)
            earliest.offsetGaps.assert().isEqualTo(1L)
            earliest.close()
        }

    @Test
    fun `a read past the end of its partition should fail after the topic is created again`(): Unit = runBlocking {
        val made = ArrayDeque(listOf(consumerWith(0, 3, 0), consumerWith(0, 3, 0)))
        val source = KafkaPartitionedSource({ made.removeFirst() }, pollTimeout = 1.milliseconds)
        val tail = runCatching { withTimeout(10.seconds) { source.read(topic, 0, 10).toList() } }.exceptionOrNull()
        (tail as OffsetGapException).message.assert().contains("ends at 3, before offset 10")
        val bounded = runCatching { withTimeout(10.seconds) { source.read(topic, 0, 10, 20).toList() } }
        bounded.exceptionOrNull().assert().isInstanceOf(OffsetGapException::class.java)
        source.offsetGaps.assert().isEqualTo(2L)
        source.close()
    }

    @Test
    fun `a tail whose offset the retention deletes while it reads should fail alone`(): Unit = runBlocking {
        val mock = consumerWith(0, 0, 0, 1)
        val source = KafkaPartitionedSource({ mock }, pollTimeout = 1.milliseconds)
        val zero = TopicPartition(topic, 0)
        val failing = async(Dispatchers.Default) { runCatching { source.read(topic, 0, 0).toList() }.exceptionOrNull() }
        val other = async(Dispatchers.Default) { source.read(topic, 1, 0).take(1).toList() }
        eventually { mock.assignment().size == 2 }
        mock.updateBeginningOffsets(mapOf(zero to 4L))
        mock.updateEndOffsets(mapOf(zero to 6L))
        mock.setPollException(OffsetOutOfRangeException("deleted", mapOf(zero to 0L)))
        withTimeout(10.seconds) { failing.await() }.assert().isInstanceOf(OffsetGapException::class.java)
        eventually { mock.assignment() == setOf(TopicPartition(topic, 1)) }
        mock.addRecord(record(0, "b0", partition = 1))
        withTimeout(10.seconds) { other.await() }.map { String(it.value) }.assert().containsExactly("b0")
        source.offsetGaps.assert().isEqualTo(1L)
        source.close()
    }

    @Test
    fun `a bounded read whose offset the retention deletes while it reads should go on from the earliest if asked`():
        Unit = runBlocking {
        val mock = consumerWith(0, 6, 0)
        val zero = TopicPartition(topic, 0)
        mock.schedulePollTask {
            mock.updateBeginningOffsets(mapOf(zero to 4L))
            mock.setPollException(OffsetOutOfRangeException("deleted", mapOf(zero to 0L)))
        }
        mock.schedulePollTask { (4L..5L).forEach { mock.addRecord(record(it, "v$it")) } }
        val source = KafkaPartitionedSource({ mock }, 1.milliseconds, Dispatchers.IO, OffsetGapPolicy.EARLIEST)
        withTimeout(10.seconds) { source.read(topic, 0, 0, 6).toList() }.map { it.offset }
            .assert().containsExactly(4L, 5L)
        source.offsetGaps.assert().isEqualTo(1L)
        source.close()
    }

    @Test
    fun `a join or leave waking the consumer while it places a deleted offset again should not end the session`():
        Unit = runBlocking {
        // Finding R2.2-3: the wakeup escaped the out-of-range handling and failed every tail of the consumer.
        val zero = TopicPartition(topic, 0)
        var wakeups = 0
        val mock = object : MockConsumer<String, ByteArray>("earliest") {
            var waking = false

            override fun beginningOffsets(partitions: Collection<TopicPartition>): Map<TopicPartition, Long> {
                if (waking) {
                    waking = false
                    wakeups++
                    throw WakeupException()
                }
                return super.beginningOffsets(partitions)
            }
        }
        mock.updateBeginningOffsets(mapOf(zero to 0L))
        mock.updateEndOffsets(mapOf(zero to 6L))
        mock.schedulePollTask {
            mock.updateBeginningOffsets(mapOf(zero to 4L))
            mock.waking = true
            mock.setPollException(OffsetOutOfRangeException("deleted", mapOf(zero to 0L)))
        }
        mock.schedulePollTask { (4L..5L).forEach { mock.addRecord(record(it, "v$it")) } }
        var made = 0
        val source = KafkaPartitionedSource(
            {
                made++
                mock
            },
            1.milliseconds,
            Dispatchers.IO,
            OffsetGapPolicy.EARLIEST,
        )
        // A tail: it shares the topic's consumer, which joins and leaves wake.
        withTimeout(10.seconds) { source.read(topic, 0, 0).take(2).toList() }.map { it.offset }
            .assert().containsExactly(4L, 5L)
        wakeups.assert().isEqualTo(1)
        made.assert().isEqualTo(1)
        source.offsetGaps.assert().isEqualTo(1L)
        source.close()
    }

    @Test
    fun `a bounded read should complete once its consumer passed the bound, and a key map to its partition`(): Unit =
        runBlocking {
            val mock = MockConsumer<String, ByteArray>("earliest")
            val zero = TopicPartition(topic, 0)
            mock.updateEndOffsets(mapOf(zero to 3L))
            mock.updateBeginningOffsets(mapOf(zero to 0L))
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
