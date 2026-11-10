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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What closing a Kafka transport's parts closes before it returns, how long it waits, and what it refuses after. */
class KafkaTransportCloseTest {
    /**
     * A consumer whose poll waits until it is woken, as a broker's would for its timeout, then throws as the client does;
     * [polling] once a poll waits. Unwoken, a poll gives up after a while instead of holding the test.
     */
    private class WaitingConsumer : MockConsumer<String, ByteArray>("earliest") {
        val polling = CountDownLatch(1)
        private val woken = CountDownLatch(1)

        override fun poll(timeout: Duration): ConsumerRecords<String, ByteArray> {
            polling.countDown()
            if (woken.await(UNWOKEN_SECONDS, TimeUnit.SECONDS)) throw WakeupException()
            return ConsumerRecords.empty()
        }

        override fun wakeup() = woken.countDown()
    }

    /**
     * A consumer that, as a Kafka client does, refuses to close while another thread is in one of its calls:
     * [partitionsFor] waits until it is woken, [asking] once it waits.
     */
    private class AskedConsumer : MockConsumer<String, ByteArray>("earliest") {
        val asking = CountDownLatch(1)
        private val woken = CountDownLatch(1)

        @Volatile
        private var busy = false

        override fun partitionsFor(topic: String): List<PartitionInfo> {
            busy = true
            try {
                asking.countDown()
                if (woken.await(UNWOKEN_SECONDS, TimeUnit.SECONDS)) throw WakeupException()
                return emptyList()
            } finally {
                busy = false
            }
        }

        override fun wakeup() = woken.countDown()

        override fun close() {
            if (busy) throw ConcurrentModificationException("KafkaConsumer is not safe for multi-threaded access")
            super.close()
        }
    }

    @Test
    fun `a source's close should return once its tailing consumer has closed, failing the tail`(): Unit =
        runBlocking {
            val tail = WaitingConsumer().apply {
                val partition = TopicPartition("orders", 0)
                updateBeginningOffsets(mapOf(partition to 0L))
                updateEndOffsets(mapOf(partition to 0L))
            }
            val source = KafkaPartitionedSource({ tail }, pollTimeout = 1.milliseconds, dispatcher = Dispatchers.IO)
            val reading = async(Dispatchers.Default) { runCatching { source.read("orders", 0, 0).toList() } }
            tail.polling.await()
            source.close()
            tail.closed().assert().isTrue()
            reading.await().exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("closed")
        }

    @Test
    fun `a closed source should refuse every call, and make no consumer for it`(): Unit = runBlocking {
        var made = 0
        val source = KafkaPartitionedSource({
            made++
            MockConsumer("earliest")
        })
        source.close()
        assertThrows<IllegalStateException> { source.read("orders", 0, 0).toList() }
        assertThrows<IllegalStateException> { source.read("orders", 0, 0, 10).toList() }
        assertThrows<IllegalStateException> { source.partitions("orders") }
        assertThrows<IllegalStateException> { source.offsets("orders", 0) }
        made.assert().isZero()
    }

    @Test
    fun `a close should close the metadata consumer under its lock, failing its call in flight as closed`(): Unit =
        runBlocking {
            val metadata = AskedConsumer()
            val source = KafkaPartitionedSource({ metadata }, dispatcher = Dispatchers.IO)
            val asked = async(Dispatchers.Default) { runCatching { source.partitions("orders") } }
            metadata.asking.await()
            source.close()
            metadata.closed().assert().isTrue()
            // As a call after the close fails, not with the client's own exception.
            asked.await().exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
                .hasMessage("The Kafka source is closed.")
        }

    @Test
    fun `a topic's shared consumer closed should refuse a join, as a read that took it before the close makes`() {
        val shared = SharedTopicConsumer("orders", { MockConsumer("earliest") }, 1.milliseconds, Dispatchers.IO)
        shared.close()
        assertThrows<IllegalStateException> { shared.join(0, 0) }.message.assert().contains("closed")
    }

    @Test
    fun `a close while the session still makes its consumer should return only once that consumer is closed`(): Unit =
        runBlocking {
            val making = CountDownLatch(1)
            val made = CountDownLatch(1)
            val events = CopyOnWriteArrayList<String>()
            val consumer = object : MockConsumer<String, ByteArray>("earliest") {
                override fun close() {
                    events += "consumer closed"
                    super.close()
                }
            }
            val shared = SharedTopicConsumer(
                "orders",
                {
                    making.countDown()
                    made.await(UNWOKEN_SECONDS, TimeUnit.SECONDS)
                    consumer
                },
                1.milliseconds,
                Dispatchers.IO,
            )
            val reader = checkNotNull(shared.join(0, 0))
            making.await()
            // Nothing to wake yet: the close has to wait for the session, which closes the consumer it is given.
            val closing = async(Dispatchers.Default) {
                shared.close()
                events += "close returned"
            }
            // The reader's channel closes as the close begins; only then is the consumer made.
            reader.channel.receiveCatching().isClosed.assert().isTrue()
            made.countDown()
            closing.await()
            events.assert().containsExactly("consumer closed", "close returned")
        }

    @Test
    fun `the producer and the admin should close within a bound of their own`() {
        val producer = mockk<Producer<String, ByteArray>>(relaxed = true)
        val admin = mockk<Admin>(relaxed = true)
        val warned = warnings(KafkaTopicProducer::class.java, KafkaTopicAdmin::class.java) {
            KafkaTopicProducer(producer).close()
            KafkaTopicAdmin(admin).close()
        }
        verify(exactly = 1) { producer.close(Duration.ofSeconds(5)) }
        verify(exactly = 1) { admin.close(Duration.ofSeconds(5)) }
        // Closed within it: nothing was left to fail.
        warned.assert().isEmpty()
    }

    @Test
    fun `a close that takes its whole bound should warn that what was in flight was failed`() {
        val warned = warnings(KafkaTopicProducer::class.java, KafkaTopicAdmin::class.java) {
            KafkaTopicProducer(mockk(relaxed = true), Dispatchers.IO, 0.seconds).close()
            KafkaTopicAdmin(mockk(relaxed = true), null, emptyMap(), 1.seconds, Dispatchers.IO, 0.seconds).close()
        }
        warned.assert().containsExactly(
            "The Kafka producer's close took its whole bound of 0s: it failed the records it had not delivered, " +
                "which their senders send again.",
            "The Kafka admin's close took its whole bound of 0s: it failed the calls still in flight.",
        )
    }

    /** The warnings the loggers of [types] log while [block] runs. */
    private fun warnings(vararg types: Class<*>, block: () -> Unit): List<String> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val loggers = types.map { LoggerFactory.getLogger(it) as Logger }
        loggers.forEach { it.addAppender(appender) }
        try {
            block()
        } finally {
            loggers.forEach { it.detachAppender(appender) }
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    private companion object {
        const val UNWOKEN_SECONDS = 10L
    }
}
