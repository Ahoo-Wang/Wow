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

package me.ahoo.wow.spring.boot.starter.kafka

import me.ahoo.wow.api.Wow
import me.ahoo.wow.spring.boot.starter.EnabledCapable
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.SenderOptions

@ConfigurationProperties(prefix = KafkaProperties.PREFIX)
class KafkaProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    var bootstrapServers: List<String>,
    @DefaultValue(Wow.WOW_PREFIX) var topicPrefix: String = Wow.WOW_PREFIX,
    /**
     * common properties
     */
    val properties: Map<String, String> = mapOf(),
    val producer: Map<String, String> = mapOf(),
    val consumer: Map<String, String> = mapOf()
) : EnabledCapable {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}kafka"
    }

    fun buildSenderOptions(): SenderOptions<String, String> {
        val senderProperties = buildMap {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers.joinToString(","))
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            putAll(properties)
            putAll(producer)
        }
        return SenderOptions.create(senderProperties)
    }

    fun buildReceiverOptions(): ReceiverOptions<String, String> {
        val receiverProperties = buildMap {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers.joinToString(","))
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            putAll(properties)
            putAll(consumer)
        }
        return ReceiverOptions.create(receiverProperties)
    }
}
