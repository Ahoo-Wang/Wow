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

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TransportRefusedException
import org.apache.kafka.common.config.TopicConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * A message Kafka refuses for good keeps its key's later messages of the same send off the topic (#6782): a reader
 * takes a partition in offset order, and the runtime's topic source drops a version at or below the last one it
 * handled, so a later message written ahead of the refused one would have it dropped once it is sent again. Whether
 * the client refuses it, before any broker sees it, or the broker does, only the messages before it are written.
 */
class RefusedRecordOrderTest {
    private val key = "shop.order/t/o-1"

    private fun message(text: String) = TopicMessage.of(key, text.toByteArray())

    /** A message of [size] random bytes, which no compression makes smaller. */
    private fun sized(size: Int) = TopicMessage.of(key, Random(size).nextBytes(size))

    private suspend fun valuesOf(topic: String): List<String> {
        val count = source.offsets(topic, 0).end.toInt()
        if (count == 0) {
            return emptyList()
        }
        return withTimeout(30.seconds) { source.read(topic, 0, 0).take(count).toList() }
            .map { it.value.decodeToString() }
    }

    @Test
    fun `a message the client refuses should keep its key's later messages of the send off the topic`(): Unit =
        runBlocking {
            val topic = KafkaFixture.newTopic(1)
            // Twice the producer's default max.request.size (1 MiB): refused by the client, before any broker sees it.
            val tooLarge = sized(2 * 1024 * 1024)
            assertThrows<TransportRefusedException> {
                runBlocking { producer.send(topic, listOf(message("v5"), tooLarge, message("v7"))) }
            }
            valuesOf(topic).assert().containsExactly("v5")
        }

    @Test
    fun `a message the broker refuses should keep its key's later messages of the send off the topic`(): Unit =
        runBlocking {
            // Within the client's bound, past the topic's own: the broker refuses it.
            val topic = KafkaFixture.newTopic(1, configs = mapOf(TopicConfig.MAX_MESSAGE_BYTES_CONFIG to "65536"))
            assertThrows<TransportRefusedException> {
                runBlocking { producer.send(topic, listOf(message("v5"), sized(256 * 1024), message("v7"))) }
            }
            valuesOf(topic).assert().containsExactly("v5")
        }

    companion object {
        private val producer by lazy { KafkaTopicProducer.create(KafkaFixture.options) }
        private val source by lazy { KafkaPartitionedSource.create(KafkaFixture.options) }

        @JvmStatic
        @AfterAll
        fun close() {
            producer.close()
            source.close()
        }
    }
}
