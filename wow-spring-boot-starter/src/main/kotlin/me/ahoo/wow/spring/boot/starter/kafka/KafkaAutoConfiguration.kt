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

import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.kafka.CommandTopicConverter
import me.ahoo.wow.kafka.DefaultCommandTopicConverter
import me.ahoo.wow.kafka.DefaultEventStreamTopicConverter
import me.ahoo.wow.kafka.DefaultStateEventTopicConverter
import me.ahoo.wow.kafka.EventStreamTopicConverter
import me.ahoo.wow.kafka.KafkaCommandBus
import me.ahoo.wow.kafka.KafkaDomainEventBus
import me.ahoo.wow.kafka.KafkaStateEventBus
import me.ahoo.wow.kafka.NoOpReceiverOptionsCustomizer
import me.ahoo.wow.kafka.ReceiverOptionsCustomizer
import me.ahoo.wow.kafka.StateEventTopicConverter
import me.ahoo.wow.spring.boot.starter.BusProperties
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.command.CommandProperties
import me.ahoo.wow.spring.boot.starter.event.EventProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

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
    fun defaultCommandTopicConverter(): CommandTopicConverter {
        return DefaultCommandTopicConverter(kafkaProperties.topicPrefix)
    }

    @Bean
    @ConditionalOnProperty(
        CommandProperties.BUS_TYPE,
        matchIfMissing = true,
        havingValue = BusProperties.Type.KAFKA_NAME,
    )
    fun kafkaCommandBus(
        topicConverter: CommandTopicConverter,
        receiverOptionsCustomizer: ReceiverOptionsCustomizer
    ): DistributedCommandBus {
        return KafkaCommandBus(
            topicConverter = topicConverter,
            senderOptions = kafkaProperties.buildSenderOptions(),
            receiverOptions = kafkaProperties.buildReceiverOptions(),
            receiverOptionsCustomizer = receiverOptionsCustomizer,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun defaultEventStreamTopicConverter(): EventStreamTopicConverter {
        return DefaultEventStreamTopicConverter(kafkaProperties.topicPrefix)
    }

    @Bean
    @ConditionalOnProperty(
        EventProperties.BUS_TYPE,
        matchIfMissing = true,
        havingValue = BusProperties.Type.KAFKA_NAME,
    )
    fun kafkaDomainEventBus(
        topicConverter: EventStreamTopicConverter,
        receiverOptionsCustomizer: ReceiverOptionsCustomizer
    ): DistributedDomainEventBus {
        return KafkaDomainEventBus(
            topicConverter = topicConverter,
            senderOptions = kafkaProperties.buildSenderOptions(),
            receiverOptions = kafkaProperties.buildReceiverOptions(),
            receiverOptionsCustomizer = receiverOptionsCustomizer,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun stateEventTopicConverter(): StateEventTopicConverter {
        return DefaultStateEventTopicConverter(kafkaProperties.topicPrefix)
    }

    @Bean
    @ConditionalOnProperty(
        StateProperties.BUS_TYPE,
        matchIfMissing = true,
        havingValue = BusProperties.Type.KAFKA_NAME,
    )
    fun kafkaStateEventBus(
        topicConverter: StateEventTopicConverter,
        receiverOptionsCustomizer: ReceiverOptionsCustomizer
    ): DistributedStateEventBus {
        return KafkaStateEventBus(
            topicConverter = topicConverter,
            senderOptions = kafkaProperties.buildSenderOptions(),
            receiverOptions = kafkaProperties.buildReceiverOptions(),
            receiverOptionsCustomizer = receiverOptionsCustomizer,
        )
    }
}
