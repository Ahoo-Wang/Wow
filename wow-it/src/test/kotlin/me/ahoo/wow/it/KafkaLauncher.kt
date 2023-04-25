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

package me.ahoo.wow.it

import me.ahoo.wow.id.GlobalIdGenerator
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderOptions

object KafkaLauncher {
    private val KAFKA_CONTAINER: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.0.5"))
        .withNetworkAliases("kafka")
        .withReuse(true)
    val sender: KafkaSender<String, String>

    init {
        KAFKA_CONTAINER.start()
        sender = KafkaSender.create(senderOptions)
    }

    val isRunning = KAFKA_CONTAINER.isRunning
    val kafkaProperties: Map<String, Any>
        get() {
            return buildMap {
                put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.bootstrapServers)
                put(CommonClientConfigs.CLIENT_ID_CONFIG, "wow-test-client-${GlobalIdGenerator.generateAsString()}")
            }
        }
    val senderOptions: SenderOptions<String, String>
        get() {
            val producerProperties = buildMap {
                putAll(kafkaProperties)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            }
            return SenderOptions.create(producerProperties)
        }

    val receiverOptions: ReceiverOptions<String, String>
        get() {
            val consumerProperties = buildMap {
                putAll(kafkaProperties)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            }
            return ReceiverOptions.create(consumerProperties)
        }
}
