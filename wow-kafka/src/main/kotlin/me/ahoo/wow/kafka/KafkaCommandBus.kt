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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import reactor.kafka.receiver.ReceiverOffset
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.SenderOptions

class KafkaCommandBus(
    topicConverter: CommandTopicConverter = DefaultCommandTopicConverter(),
    senderOptions: SenderOptions<String, String>,
    receiverOptions: ReceiverOptions<String, String>,
    receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer,
    receiverPolicy: KafkaReceiverPolicy = KafkaReceiverPolicy(),
    recordDecodeFailureHandler: KafkaRecordDecodeFailureHandler = FailKafkaRecordDecodeFailureHandler,
) : DistributedCommandBus, AbstractKafkaBus<CommandMessage<*>, ServerCommandExchange<*>>(
    topicConverter,
    senderOptions,
    receiverOptions,
    receiverOptionsCustomizer,
    receiverPolicy,
    recordDecodeFailureHandler,
) {

    override val messageType: Class<CommandMessage<*>>
        get() = CommandMessage::class.java

    override fun CommandMessage<*>.toExchange(receiverOffset: ReceiverOffset): ServerCommandExchange<*> {
        return KafkaServerCommandExchange(this, receiverOffset)
    }
}
