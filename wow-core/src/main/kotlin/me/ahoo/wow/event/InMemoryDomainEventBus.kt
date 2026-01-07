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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.infra.sink.concurrent
import me.ahoo.wow.messaging.InMemoryMessageBus
import reactor.core.publisher.Sinks
import reactor.core.publisher.Sinks.Many

/**
 * In-memory implementation of LocalDomainEventBus.
 *
 * This class provides an in-memory message bus for domain events, suitable for
 * testing or single-process applications. It uses reactive sinks to handle
 * event publishing and subscription within the same JVM instance.
 *
 * @property sinkSupplier Function to create reactive sinks for each named aggregate (default: multicast sink with buffer)
 *
 * @constructor Creates a new InMemoryDomainEventBus with the specified sink supplier
 *
 * @param sinkSupplier The function to create sinks for named aggregates
 *
 * @see LocalDomainEventBus
 * @see InMemoryMessageBus
 * @see Sinks.Many
 */
class InMemoryDomainEventBus(
    override val sinkSupplier: (NamedAggregate) -> Many<DomainEventStream> = {
        Sinks.unsafe().many().multicast().onBackpressureBuffer<DomainEventStream>().concurrent()
    }
) : InMemoryMessageBus<DomainEventStream, EventStreamExchange>(),
    LocalDomainEventBus {
    /**
     * Creates an EventStreamExchange from a DomainEventStream.
     *
     * @param message The domain event stream to wrap
     * @return A new SimpleEventStreamExchange containing the message
     *
     * @see EventStreamExchange
     * @see SimpleEventStreamExchange
     */
    override fun DomainEventStream.createExchange(): EventStreamExchange = SimpleEventStreamExchange(this)
}
