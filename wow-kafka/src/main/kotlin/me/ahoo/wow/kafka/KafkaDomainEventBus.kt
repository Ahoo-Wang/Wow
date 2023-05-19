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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.messaging.DistributedMessageBus
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kafka.receiver.ReceiverOffset
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.SenderOptions

class KafkaDomainEventBus(
    senderOptions: SenderOptions<String, String>,
    receiverOptions: ReceiverOptions<String, String>,
    private val topicPrefix: String = Wow.WOW_PREFIX,
    receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer
) : DomainEventBus, DistributedMessageBus,
    AbstractKafkaBus<DomainEventStream, EventStreamExchange>(
        senderOptions,
        receiverOptions,
        receiverOptionsCustomizer
    ) {

    override val messageType: Class<DomainEventStream>
        get() = DomainEventStream::class.java

    override fun NamedAggregate.asTopic(): String {
        return asEventStreamTopic(topicPrefix)
    }

    override fun DomainEventStream.asExchange(receiverOffset: ReceiverOffset): EventStreamExchange {
        return KafkaEventStreamExchange(this, receiverOffset)
    }

    override fun send(message: DomainEventStream): Mono<Void> {
        return super.sendMessage(message)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> {
        return super.receiveMessage(namedAggregates)
    }
}
