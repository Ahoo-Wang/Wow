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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TransportRefusedException
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A send's order per key (#6782), over `kafka-clients`' mock producer, which acknowledges nothing until told: a key's
 * next message goes to the broker only once its previous one was acknowledged, whatever the other keys wait for.
 *
 * A key's next message is handed only after an acknowledgement this test gives, so while it gives none, what the mock
 * was handed is all the send can hand: the counts below are exact for that reason, not by timing.
 */
class KafkaTopicProducerTest {
    private val topic = "wow.shop.order.state"
    private val mock = MockProducer(false, null, StringSerializer(), ByteArraySerializer())
    private val producer = KafkaTopicProducer(mock)

    private fun message(key: String, version: Int) = TopicMessage.of(key, "$key$version".toByteArray())

    private fun handed(): List<String> = mock.history().map { it.value().decodeToString() }

    /** Waits until the mock was handed [count] messages. */
    private suspend fun awaitHanded(count: Int) = withTimeout(10.seconds) {
        while (mock.history().size < count) {
            delay(1)
        }
    }

    @Test
    fun `a key's next message should go to the broker only once its previous one is acknowledged`(): Unit =
        runBlocking {
            val messages = listOf(message("a", 1), message("b", 1), message("a", 2), message("c", 1), message("a", 3))
            val sending = async(Dispatchers.Default) { producer.send(topic, messages) }
            awaitHanded(3)
            handed().assert().containsExactly("a1", "b1", "c1")
            repeat(3) { mock.completeNext().assert().isTrue() }
            awaitHanded(4)
            handed().assert().containsExactly("a1", "b1", "c1", "a2")
            mock.completeNext().assert().isTrue()
            awaitHanded(5)
            handed().last().assert().isEqualTo("a3")
            mock.completeNext().assert().isTrue()
            sending.await()
        }

    @Test
    fun `a key should go on once its own message is acknowledged, whatever another key waits for`(): Unit =
        runBlocking {
            val messages = listOf(message("a", 1), message("b", 1), message("a", 2), message("b", 2))
            val sending = async(Dispatchers.Default) { producer.send(topic, messages) }
            awaitHanded(2)
            // a1 acknowledged, b1 not: a2 goes, b2 waits for b1.
            mock.completeNext().assert().isTrue()
            awaitHanded(3)
            handed().assert().containsExactly("a1", "b1", "a2")
            mock.completeNext().assert().isTrue()
            awaitHanded(4)
            handed().last().assert().isEqualTo("b2")
            repeat(2) { mock.completeNext().assert().isTrue() }
            sending.await()
        }

    @Test
    fun `messages of distinct keys should all go to the broker before any is acknowledged, in one round`(): Unit =
        runBlocking {
            val messages = (1..32).map { message("k$it-", 1) }
            val sending = async(Dispatchers.Default) { producer.send(topic, messages) }
            // One round trip for the whole call: all 32 handed while none is acknowledged, as before #6782.
            awaitHanded(32)
            handed().assert().isEqualTo(messages.map { it.value.decodeToString() })
            repeat(32) { mock.completeNext().assert().isTrue() }
            sending.await()
            mock.history().size.assert().isEqualTo(32)
        }

    @Test
    fun `a message refused for good should stop its key's later messages of the call, which fail with it`(): Unit =
        runBlocking {
            val messages = listOf(message("a", 1), message("b", 1), message("a", 2), message("b", 2), message("a", 3))
            val sending = async(Dispatchers.Default) { runCatching { producer.send(topic, messages) } }
            awaitHanded(2)
            mock.errorNext(RecordTooLargeException("too large")).assert().isTrue()
            mock.completeNext().assert().isTrue()
            // The other key goes on; a2 and a3 are never handed to the broker.
            awaitHanded(3)
            mock.completeNext().assert().isTrue()
            sending.await().exceptionOrNull().assert().isInstanceOf(TransportRefusedException::class.java)
            handed().assert().containsExactly("a1", "b1", "b2")
        }
}
