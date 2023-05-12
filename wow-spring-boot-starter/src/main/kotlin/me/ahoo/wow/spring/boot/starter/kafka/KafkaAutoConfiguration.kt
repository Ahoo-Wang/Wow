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

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.kafka.KafkaCommandBus
import me.ahoo.wow.kafka.KafkaDomainEventBus
import me.ahoo.wow.kafka.NoOpReceiverOptionsCustomizer
import me.ahoo.wow.kafka.ReceiverOptionsCustomizer
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.MessageBusType
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.command.CommandProperties
import me.ahoo.wow.spring.boot.starter.event.EventProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import reactor.kafka.sender.KafkaSender

@AutoConfiguration(before = [CommandAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnKafkaEnabled
@ConditionalOnClass(KafkaCommandBus::class)
@EnableConfigurationProperties(KafkaProperties::class)
class KafkaAutoConfiguration(private val kafkaProperties: KafkaProperties) {

    @Bean
    @ConditionalOnMissingBean
    fun receiverOptionsCustomizer(): ReceiverOptionsCustomizer {
        return NoOpReceiverOptionsCustomizer
    }

    @Bean
    @ConditionalOnMissingBean
    fun kafkaSender(): KafkaSender<String, String> {
        return KafkaSender.create(kafkaProperties.buildSenderOptions())
    }

    @Bean
    @ConditionalOnProperty(
        CommandProperties.Bus.TYPE,
        matchIfMissing = true,
        havingValue = MessageBusType.KAFKA_NAME,
    )
    fun kafkaCommandBus(
        kafkaSender: KafkaSender<String, String>,
        receiverOptionsCustomizer: ReceiverOptionsCustomizer
    ): CommandBus {
        return KafkaCommandBus(
            sender = kafkaSender,
            receiverOptions = kafkaProperties.buildReceiverOptions(),
            topicPrefix = kafkaProperties.topicPrefix,
            receiverOptionsCustomizer = receiverOptionsCustomizer
        )
    }

    @Bean
    @ConditionalOnProperty(
        EventProperties.Bus.TYPE,
        matchIfMissing = true,
        havingValue = MessageBusType.KAFKA_NAME,
    )
    fun kafkaDomainEventBus(
        kafkaSender: KafkaSender<String, String>,
        receiverOptionsCustomizer: ReceiverOptionsCustomizer
    ): DomainEventBus {
        return KafkaDomainEventBus(
            sender = kafkaSender,
            receiverOptions = kafkaProperties.buildReceiverOptions(),
            topicPrefix = kafkaProperties.topicPrefix,
            receiverOptionsCustomizer = receiverOptionsCustomizer
        )
    }
}
