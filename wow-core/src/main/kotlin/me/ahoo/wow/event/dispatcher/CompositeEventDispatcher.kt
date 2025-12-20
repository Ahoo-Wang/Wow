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
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import reactor.core.publisher.Mono

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
            schedulerSupplier = schedulerSupplier
        )
    }

    private val stateEventDispatcher by lazy {
        StateEventDispatcher(
            name = name,
            parallelism = parallelism,
            messageBus = stateEventBus,
            functionRegistrar = functionRegistrar.filter { it.functionKind == FunctionKind.STATE_EVENT },
            eventHandler = eventHandler,
            schedulerSupplier = schedulerSupplier
        )
    }

    override fun run() {
        eventStreamDispatcher.run()
        stateEventDispatcher.run()
    }

    override fun close() {
        eventStreamDispatcher.close()
        stateEventDispatcher.close()
    }
}
