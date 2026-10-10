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

package me.ahoo.wow.event.transport

import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.messaging.transport.TopicNaming
import me.ahoo.wow.messaging.transport.Transport
import me.ahoo.wow.messaging.transport.TransportDecodeFailureHandler
import me.ahoo.wow.messaging.transport.TransportMessageBus
import me.ahoo.wow.messaging.transport.TransportRecord
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * The distributed domain event bus over a [Transport].
 */
open class TransportDomainEventBus(
    transport: Transport,
    topicNaming: TopicNaming,
    decodeFailureHandler: TransportDecodeFailureHandler = TransportDecodeFailureHandler.FAIL,
) : TransportMessageBus<DomainEventStream, EventStreamExchange>(transport, topicNaming, decodeFailureHandler),
    DistributedDomainEventBus {
    override val messageType: Class<DomainEventStream>
        get() = DomainEventStream::class.java

    override fun createExchange(message: DomainEventStream, record: TransportRecord): EventStreamExchange =
        TransportEventStreamExchange(message, record)
}

/** An event stream received from a [Transport]; acknowledging it acknowledges its [record]. */
class TransportEventStreamExchange(
    override val message: DomainEventStream,
    val record: TransportRecord,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : EventStreamExchange {
    override fun acknowledge(): Mono<Void> = record.ack()
}
