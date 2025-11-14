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

import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.scheduler.DefaultAggregateSchedulerSupplier
import reactor.core.publisher.Mono

/**
 * Domain Event Dispatcher responsible for coordinating the processing of domain events.
 *
 * This class extends AbstractEventDispatcher to provide concrete implementation for
 * handling domain events through the event bus system. It manages the lifecycle
 * of event processing, including subscription to event streams and distribution
 * of events to appropriate handlers.
 *
 * @property name The name of this dispatcher, typically formatted as `applicationName.DomainEventDispatcher`
 * @property parallelism The level of parallelism for processing events (default: DEFAULT_PARALLELISM)
 * @property domainEventBus The domain event bus for publishing and subscribing to domain events
 * @property stateEventBus The state event bus for handling state-related events
 * @property functionRegistrar The registrar for domain event handler functions
 * @property eventHandler The event handler for processing domain events
 * @property schedulerSupplier Supplier for creating schedulers for aggregate processing
 *
 * @see AbstractEventDispatcher
 * @see DomainEventBus
 * @see StateEventBus
 * @see DomainEventFunctionRegistrar
 * @see DomainEventHandler
 */
class DomainEventDispatcher(
    /**
     * The name of this dispatcher, typically formatted as `applicationName.DomainEventDispatcher`.
     */
    override val name: String,
    /**
     * The level of parallelism for processing events.
     * @default MessageParallelism.DEFAULT_PARALLELISM
     */
    override val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    /**
     * The domain event bus for publishing and subscribing to domain events.
     */
    override val domainEventBus: DomainEventBus,
    /**
     * The state event bus for handling state-related events.
     */
    override val stateEventBus: StateEventBus,
    /**
     * The registrar for domain event handler functions.
     */
    override val functionRegistrar: DomainEventFunctionRegistrar,
    /**
     * The event handler for processing domain events.
     */
    override val eventHandler: DomainEventHandler,
    /**
     * Supplier for creating schedulers for aggregate processing.
     * @default DefaultAggregateSchedulerSupplier("EventDispatcher")
     */
    override val schedulerSupplier: AggregateSchedulerSupplier =
        DefaultAggregateSchedulerSupplier("EventDispatcher")
) : AbstractEventDispatcher<Mono<*>>()
