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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A record a producer compressed (`compression.type`, which Wow does not set) is read back as it was written, through
 * the codecs wow-kafka declares at their patched versions: snappy-java and lz4-java.
 */
class KafkaCompressionTest {
    @ParameterizedTest
    @ValueSource(strings = ["snappy", "lz4"])
    fun `a record a producer compressed is read back as written`(codec: String): Unit = runBlocking {
        val topic = KafkaFixture.newTopic(1)
        val body = "compressed ".repeat(200).toByteArray()
        val compressing = KafkaOptions(KafkaFixture.options.bootstrapServers, mapOf("compression.type" to codec))
        KafkaTopicProducer.create(compressing).use { producer -> producer.send(topic, TopicMessage.of("k", body)) }
        KafkaPartitionedSource.create(KafkaFixture.options).use { source ->
            val read = source.read(topic, 0, 0, 1).toList().single()
            read.value.assert().isEqualTo(body)
        }
    }
}
