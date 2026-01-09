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

package me.ahoo.wow.saga.stateless

import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.dispatcher.CompositeEventDispatcher
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.dispatcher.MessageParallelism

/**
 * Dispatcher for stateless sagas that handles domain events and coordinates command execution.
 * This dispatcher extends [CompositeEventDispatcher] to provide event-driven processing for sagas
 * that don't maintain state between events.
 *
 * @property name The name of the dispatcher, typically formatted as `applicationName.StatelessSagaDispatcher`.
 * @property parallelism The number of parallel threads for processing messages (default: [MessageParallelism.DEFAULT_PARALLELISM]).
 * @property domainEventBus The bus for publishing domain events.
 * @property stateEventBus The bus for publishing state events.
 * @property functionRegistrar The registrar for stateless saga functions.
 * @property eventHandler The handler for processing domain events.
 */
class StatelessSagaDispatcher(
    /**
     * named like `applicationName.StatelessSagaDispatcher`
     */
    name: String,
    parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    domainEventBus: DomainEventBus,
    stateEventBus: StateEventBus,
    functionRegistrar: StatelessSagaFunctionRegistrar,
    eventHandler: StatelessSagaHandler
) : CompositeEventDispatcher(
    name = name,
    parallelism = parallelism,
    domainEventBus = domainEventBus,
    stateEventBus = stateEventBus,
    functionRegistrar = functionRegistrar,
    eventHandler = eventHandler,
)
