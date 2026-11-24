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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

/**
 * Review r1 (transport): a batch the sink hands the producer holds an aggregate's records in version order. When
 * Kafka refuses one for good (a state past `max.request.size`), the records after it, of the same key, must not reach
 * the topic ahead of it: a reader takes them in offset order, and the runtime's topic source drops a version at or
 * below the last one it handled, so the refused record, published once an operator fixes it, would be dropped.
 */
class ReviewRefusedRecordOrderTest {
    private val key = "shop.order/t/o-1"

    private fun message(text: String) = TopicMessage.of(key, text.toByteArray())

    private suspend fun valuesOf(topic: String): List<String> {
        val count = source.offsets(topic, 0).end.toInt()
        if (count == 0) {
            return emptyList()
        }
        return withTimeout(30.seconds) { source.read(topic, 0, 0).take(count).toList() }
            .map { it.value.decodeToString() }
    }

    @Test
    fun `a record Kafka refuses should keep its key's later records of the batch off the topic`(): Unit =
        runBlocking {
            val topic = KafkaFixture.newTopic(1)
            // Twice the producer's default max.request.size (1 MiB): refused by the client, before any broker sees it.
            val tooLarge = TopicMessage.of(key, ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() })
            assertThrows<TransportRefusedException> {
                runBlocking { producer.send(topic, listOf(message("v5"), tooLarge, message("v7"))) }
            }
            // Fails: v7 is on the topic, ahead of v6, which was never written.
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
