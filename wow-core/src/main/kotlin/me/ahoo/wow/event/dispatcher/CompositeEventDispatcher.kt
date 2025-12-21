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

package me.ahoo.wow.event.dispatcher

import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.dispatcher.MessageDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import reactor.core.publisher.Mono

/**
 * A composite event dispatcher that combines event stream and state event dispatchers to handle domain events and state events efficiently.
 *
 * This class implements the [MessageDispatcher] interface and delegates event processing to two specialized dispatchers:
 * - [EventStreamDispatcher] for handling domain event streams.
 * - [StateEventDispatcher] for handling state-related events.
 *
 * It provides a unified way to start and stop both dispatchers, ensuring proper lifecycle management and parallelism control.
 *
 * Example usage:
 * ```
 * val dispatcher = CompositeEventDispatcher(
 *     name = "MyApp.DomainEventDispatcher",
 *     parallelism = 4,
 *     domainEventBus = myDomainEventBus,
 *     stateEventBus = myStateEventBus,
 *     functionRegistrar = myFunctionRegistrar,
 *     eventHandler = myEventHandler,
 *     schedulerSupplier = mySchedulerSupplier
 * )
 * dispatcher.start()
 * // ... application logic ...
 * dispatcher.stopGracefully().block()
 * ```
 *
 * @param name The name of this dispatcher, typically formatted as `applicationName.DomainEventDispatcher`.
 * @param parallelism The level of parallelism for processing events. Defaults to [MessageParallelism.DEFAULT_PARALLELISM].
 * @param domainEventBus The domain event bus for publishing and subscribing to domain events.
 * @param stateEventBus The state event bus for handling state-related events.
 * @param functionRegistrar The registrar for domain event handler functions.
 * @param eventHandler The event handler for processing domain events.
 * @param schedulerSupplier Supplier for creating schedulers for aggregate processing. Defaults to a default implementation.
 *
 * @see EventStreamDispatcher
 * @see StateEventDispatcher
 * @see MessageDispatcher
 */
open class CompositeEventDispatcher(
    /**
     * The name of this dispatcher, typically formatted as `applicationName.DomainEventDispatcher`.
     */
    override val name: String,
    /**
     * The level of parallelism for processing events.
     * @default MessageParallelism.DEFAULT_PARALLELISM
     */
    private val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    /**
     * The domain event bus for publishing and subscribing to domain events.
     */
    private val domainEventBus: DomainEventBus,
    /**
     * The state event bus for handling state-related events.
     */
    private val stateEventBus: StateEventBus,
    /**
     * The registrar for domain event handler functions.
     */
    private val functionRegistrar: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>,
    /**
     * The event handler for processing domain events.
     */
    private val eventHandler: EventHandler,
    /**
     * Supplier for creating schedulers for aggregate processing.
     * @default DefaultAggregateSchedulerSupplier("EventDispatcher")
     */
    private val schedulerSupplier: AggregateSchedulerSupplier
) : MessageDispatcher {
    private val eventStreamDispatcher by lazy {
        EventStreamDispatcher(
            name = name,
            parallelism = parallelism,
            messageBus = domainEventBus,
            functionRegistrar = functionRegistrar.filter { it.functionKind == FunctionKind.EVENT },
            eventHandler = eventHandler,
            schedulerSupplier = schedulerSupplier,
        )
    }

    private val stateEventDispatcher by lazy {
        StateEventDispatcher(
            name = name,
            parallelism = parallelism,
            messageBus = stateEventBus,
            functionRegistrar = functionRegistrar.filter { it.functionKind == FunctionKind.STATE_EVENT },
            eventHandler = eventHandler,
            schedulerSupplier = schedulerSupplier,
        )
    }

    /**
     * Starts the composite event dispatcher by initializing and starting both the event stream dispatcher and state event dispatcher.
     *
     * This method ensures that both underlying dispatchers are started and ready to process events.
     */
    override fun start() {
        eventStreamDispatcher.start()
        stateEventDispatcher.start()
    }

    /**
     * Stops the composite event dispatcher gracefully by stopping both the event stream dispatcher and state event dispatcher.
     *
     * This method waits for both dispatchers to complete their current processing and shut down cleanly.
     *
     * @return A [Mono] that completes when both dispatchers have stopped gracefully.
     */
    override fun stopGracefully(): Mono<Void> {
        return Mono.zip(eventStreamDispatcher.stopGracefully(), stateEventDispatcher.stopGracefully()).then()
    }
}
