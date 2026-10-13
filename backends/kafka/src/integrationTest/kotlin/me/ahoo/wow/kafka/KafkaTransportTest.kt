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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.transport.TopicTransportSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

/** The transport contract on a real broker. */
class KafkaTransportTest : TopicTransportSpec() {
    override val producer: TopicProducer get() = Companion.producer
    override val source: PartitionedSource get() = Companion.source

    override suspend fun newTopic(partitions: Int): String = KafkaFixture.newTopic(partitions)

    @Test
    fun `a send to a broker that cannot be reached should fail rather than return`(): Unit = runBlocking {
        val options = KafkaOptions(
            "127.0.0.1:1",
            mapOf("max.block.ms" to "500", "request.timeout.ms" to "500", "delivery.timeout.ms" to "1000"),
        )
        KafkaTopicProducer.create(options).use { unreachable ->
            runCatching { unreachable.send("wow.it.unreachable", TopicMessage("k", byteArrayOf(1))) }
                .isFailure.assert().isTrue()
        }
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
