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

package me.ahoo.wow.tck.container

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import org.testcontainers.containers.KafkaContainer
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.SenderOptions

class KafkaTestFixture(
    private val clientPrefix: String = "wow-test-client",
) : BeforeEachCallback, TestWatcher {
    private var kafkaContainer: KafkaContainer? = null

    override fun beforeEach(context: ExtensionContext) {
        kafka().isRunning
    }

    private fun kafka(): KafkaContainer {
        return kafkaContainer ?: WowTestContainers.kafka
            .also {
                kafkaContainer = it
            }
    }

    fun topic(prefix: String): String {
        return ContainerTestIds.nextName(prefix)
    }

    fun clientId(suffix: String = "client"): String {
        val prefix = "${clientPrefix}_$suffix"
        return runCatching {
            ContainerTestIds.nextName(prefix)
        }.getOrElse {
            throw IllegalArgumentException(
                "Kafka client id prefix '$prefix' must normalize to 1-30 lowercase letters, " +
                    "digits, or underscores and start with a letter.",
                it,
            )
        }
    }

    fun kafkaProperties(clientId: String = clientId()): Map<String, Any> {
        return buildMap {
            put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, kafka().bootstrapServers)
            put(CommonClientConfigs.CLIENT_ID_CONFIG, clientId)
        }
    }

    fun senderOptions(clientId: String = clientId("sender")): SenderOptions<String, String> {
        val producerProperties = buildMap {
            putAll(kafkaProperties(clientId))
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        }
        return SenderOptions.create(producerProperties)
    }

    fun receiverOptions(
        clientId: String = clientId("receiver"),
        groupId: String = clientId("group"),
    ): ReceiverOptions<String, String> {
        val consumerProperties = buildMap {
            putAll(kafkaProperties(clientId))
            put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        }
        return ReceiverOptions.create(consumerProperties)
    }

    override fun testFailed(context: ExtensionContext, cause: Throwable?) {
        ContainerDiagnostics.printFailure("kafka", kafkaContainer, cause)
    }
}
