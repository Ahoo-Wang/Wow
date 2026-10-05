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

import me.ahoo.wow.messaging.transport.TopicNaming
import me.ahoo.wow.messaging.transport.Transport
import me.ahoo.wow.messaging.transport.TransportDecodeFailureHandler
import me.ahoo.wow.messaging.transport.TransportDomainEventBus
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.SenderOptions

/**
 * The domain event stream bus on Kafka: a [TransportDomainEventBus] over a [KafkaTransport], with topics from [topicConverter].
 */
class KafkaDomainEventBus(
    transport: Transport,
    topicConverter: EventStreamTopicConverter = DefaultEventStreamTopicConverter(),
    decodeFailureHandler: TransportDecodeFailureHandler = TransportDecodeFailureHandler.FAIL,
) : TransportDomainEventBus(transport, TopicNaming(topicConverter::convert), decodeFailureHandler) {
    constructor(
        topicConverter: EventStreamTopicConverter = DefaultEventStreamTopicConverter(),
        senderOptions: SenderOptions<String, String>,
        receiverOptions: ReceiverOptions<String, String>,
        receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer,
        receiverPolicy: KafkaReceiverPolicy = KafkaReceiverPolicy(),
        decodeFailureHandler: TransportDecodeFailureHandler = TransportDecodeFailureHandler.FAIL,
    ) : this(
        transport = KafkaTransport(senderOptions, receiverOptions, receiverOptionsCustomizer, receiverPolicy),
        topicConverter = topicConverter,
        decodeFailureHandler = decodeFailureHandler,
    )
}
