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
import me.ahoo.wow.messaging.transport.TransportFailurePolicy
import me.ahoo.wow.messaging.transport.TransportStateEventBus
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.SenderOptions

/**
 * The state event bus on Kafka: a [TransportStateEventBus] over a [KafkaTransport], with topics from [topicConverter].
 */
class KafkaStateEventBus(
    transport: Transport,
    topicConverter: StateEventTopicConverter = DefaultStateEventTopicConverter(),
    decodeFailureHandler: TransportDecodeFailureHandler = TransportDecodeFailureHandler.FAIL,
) : TransportStateEventBus(transport, TopicNaming(topicConverter::convert), decodeFailureHandler) {
    constructor(
        topicConverter: StateEventTopicConverter = DefaultStateEventTopicConverter(),
        senderOptions: SenderOptions<String, String>,
        receiverOptions: ReceiverOptions<String, String>,
        receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer,
        receiverPolicy: KafkaReceiverPolicy = KafkaReceiverPolicy(),
        decodeFailureHandler: TransportDecodeFailureHandler = TransportDecodeFailureHandler.FAIL,
        failurePolicy: TransportFailurePolicy = TransportFailurePolicy.DEFAULT,
    ) : this(
        transport = KafkaTransport(
            senderOptions = senderOptions,
            receiverOptions = receiverOptions,
            receiverOptionsCustomizer = receiverOptionsCustomizer,
            receiverPolicy = receiverPolicy,
            failurePolicy = failurePolicy,
        ),
        topicConverter = topicConverter,
        decodeFailureHandler = decodeFailureHandler,
    )
}
