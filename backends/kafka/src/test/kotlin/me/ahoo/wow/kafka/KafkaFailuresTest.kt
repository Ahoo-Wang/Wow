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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TransportUnavailableException
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.errors.BrokerNotAvailableException
import org.apache.kafka.common.errors.CoordinatorNotAvailableException
import org.apache.kafka.common.errors.DisconnectException
import org.apache.kafka.common.errors.LeaderNotAvailableException
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.errors.NotEnoughReplicasException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test

/** Kafka being unreachable is a transport unavailable; the rest stays the client's. */
class KafkaFailuresTest {
    @Test
    fun `connection failures are a transport unavailable, others stay the client's`() {
        listOf(
            BrokerNotAvailableException("down"),
            CoordinatorNotAvailableException("down"),
            LeaderNotAvailableException("electing"),
            NetworkException("reset"),
            DisconnectException("gone"),
            NotEnoughReplicasException("1 of 3"),
            TimeoutException("Expiring 1 record"),
        ).forEach { failure ->
            KafkaFailures.translate(failure).assert().isInstanceOf(TransportUnavailableException::class.java)
                .hasCause(failure)
        }
        val tooLarge = RecordTooLargeException("too large")
        KafkaFailures.translate(tooLarge).assert().isSameAs(tooLarge)
    }

    private fun failedSend(failure: RuntimeException): Throwable {
        val producer = MockProducer(false, null, StringSerializer(), ByteArraySerializer())
        val topics = KafkaTopicProducer(producer, Dispatchers.Unconfined)
        return runBlocking {
            val sent = async { runCatching { topics.send("t", listOf(TopicMessage.of("k", byteArrayOf(1)))) } }
            while (producer.history().isEmpty()) yield()
            producer.errorNext(failure)
            checkNotNull(sent.await().exceptionOrNull())
        }
    }

    @Test
    fun `a send the broker never acknowledged fails as unavailable, a refused one as it is`() {
        failedSend(TimeoutException("Expiring 1 record")).assert().isInstanceOf(TransportUnavailableException::class.java)
        failedSend(RecordTooLargeException("too large")).assert().isInstanceOf(RecordTooLargeException::class.java)
        assertThrownBy<IllegalStateException> { error("unused") }
    }
}
