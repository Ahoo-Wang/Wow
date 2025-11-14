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
package me.ahoo.wow.event

import me.ahoo.wow.api.messaging.TopicKind
import me.ahoo.wow.api.messaging.TopicKindCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.DistributedMessageBus
import me.ahoo.wow.messaging.LocalMessageBus
import me.ahoo.wow.messaging.MessageBus
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Domain Event Bus interface for publishing and subscribing to domain event streams.
 *
 * This interface defines the contract for a message bus that handles domain event streams,
 * ensuring ordered publication and processing of events per aggregate ID.
 *
 * Key characteristics:
 * 1. **Ordered Publication**: Events are published in order per aggregate ID
 * 2. **Ordered Processing**: Events are processed in the order they were published
 *
 * @see MessageBus
 * @see DomainEventStream
 * @see EventStreamExchange
 * @see TopicKindCapable
 */
interface DomainEventBus :
    MessageBus<DomainEventStream, EventStreamExchange>,
    TopicKindCapable {
    override val topicKind: TopicKind
        get() = TopicKind.EVENT_STREAM
}

/**
 * Local Domain Event Bus interface for in-process event handling.
 *
 * This interface extends DomainEventBus and LocalMessageBus to provide
 * event bus functionality within the same process or JVM instance.
 *
 * @see DomainEventBus
 * @see LocalMessageBus
 */
interface LocalDomainEventBus :
    DomainEventBus,
    LocalMessageBus<DomainEventStream, EventStreamExchange>

/**
 * Distributed Domain Event Bus interface for cross-process event handling.
 *
 * This interface extends DomainEventBus and DistributedMessageBus to provide
 * event bus functionality across multiple processes or services.
 *
 * @see DomainEventBus
 * @see DistributedMessageBus
 */
interface DistributedDomainEventBus :
    DomainEventBus,
    DistributedMessageBus<DomainEventStream, EventStreamExchange>

/**
 * No-operation implementation of DomainEventBus.
 *
 * This implementation provides a no-op (no operation) domain event bus that
 * discards all sent messages and returns empty streams for receive operations.
 * Useful for testing or when event publishing is disabled.
 *
 * @see DomainEventBus
 */
object NoOpDomainEventBus : DomainEventBus {
    /**
     * Sends a domain event stream but performs no operation.
     *
     * @param message The domain event stream to send (ignored)
     * @return A Mono that completes immediately with no value
     */
    override fun send(message: DomainEventStream): Mono<Void> = Mono.empty()

    /**
     * Receives domain event streams but returns an empty flux.
     *
     * @param namedAggregates The set of named aggregates to receive events for (ignored)
     * @return An empty Flux of event stream exchanges
     */
    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> = Flux.empty()
}
