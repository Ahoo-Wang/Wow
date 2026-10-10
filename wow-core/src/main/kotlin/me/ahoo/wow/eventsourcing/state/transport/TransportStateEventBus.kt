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

package me.ahoo.wow.eventsourcing.state.transport

import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.transport.TopicNaming
import me.ahoo.wow.messaging.transport.Transport
import me.ahoo.wow.messaging.transport.TransportDecodeFailureHandler
import me.ahoo.wow.messaging.transport.TransportMessageBus
import me.ahoo.wow.messaging.transport.TransportRecord
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * The distributed state event bus over a [Transport].
 */
open class TransportStateEventBus(
    transport: Transport,
    topicNaming: TopicNaming,
    decodeFailureHandler: TransportDecodeFailureHandler = TransportDecodeFailureHandler.FAIL,
) : TransportMessageBus<StateEvent<*>, StateEventExchange<*>>(transport, topicNaming, decodeFailureHandler),
    DistributedStateEventBus {
    override val messageType: Class<StateEvent<*>>
        get() = StateEvent::class.java

    override fun createExchange(message: StateEvent<*>, record: TransportRecord): StateEventExchange<*> =
        TransportStateEventExchange(message, record)
}

/** A state event received from a [Transport]; acknowledging it acknowledges its [record]. */
class TransportStateEventExchange<S : Any>(
    override val message: StateEvent<S>,
    val record: TransportRecord,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : StateEventExchange<S> {
    override fun acknowledge(): Mono<Void> = record.ack()
}
