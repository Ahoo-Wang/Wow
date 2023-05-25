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

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kafka.receiver.ReceiverOffset
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.SenderOptions
import reactor.util.retry.Retry
import reactor.util.retry.RetryBackoffSpec
import java.time.Duration

internal val DEFAULT_RECEIVE_RETRY_SPEC: RetryBackoffSpec = Retry.backoff(3, Duration.ofSeconds(10))

class KafkaCommandBus(
    senderOptions: SenderOptions<String, String>,
    receiverOptions: ReceiverOptions<String, String>,
    private val topicPrefix: String = Wow.WOW_PREFIX,
    receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer
) : DistributedCommandBus, AbstractKafkaBus<CommandMessage<*>, ServerCommandExchange<*>>(
    senderOptions,
    receiverOptions,
    receiverOptionsCustomizer,
) {

    @Suppress("UNCHECKED_CAST")
    override val messageType: Class<CommandMessage<*>>
        get() = CommandMessage::class.java

    override fun NamedAggregate.asTopic(): String {
        return asCommandTopic(topicPrefix)
    }

    override fun CommandMessage<*>.asExchange(receiverOffset: ReceiverOffset): ServerCommandExchange<*> {
        return KafkaServerCommandExchange(this, receiverOffset)
    }

    @Suppress("UNCHECKED_CAST")
    override fun send(message: CommandMessage<*>): Mono<Void> {
        return super.sendMessage(message as CommandMessage<Any>)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> {
        return super.receiveMessage(namedAggregates)
    }
}
