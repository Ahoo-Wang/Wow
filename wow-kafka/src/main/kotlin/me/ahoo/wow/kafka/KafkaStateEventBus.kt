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

import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import reactor.kafka.receiver.ReceiverOffset
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.SenderOptions

class KafkaStateEventBus(
    topicConverter: StateEventTopicConverter = DefaultStateEventTopicConverter(),
    senderOptions: SenderOptions<String, String>,
    receiverOptions: ReceiverOptions<String, String>,
    receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer
) : DistributedStateEventBus,
    AbstractKafkaBus<StateEvent<*>, StateEventExchange<*>>(
        topicConverter,
        senderOptions,
        receiverOptions,
        receiverOptionsCustomizer,
    ) {

    override val messageType: Class<StateEvent<*>>
        get() = StateEvent::class.java

    override fun StateEvent<*>.asExchange(receiverOffset: ReceiverOffset): StateEventExchange<*> {
        return KafkaStateEventExchange(this, receiverOffset)
    }
}
