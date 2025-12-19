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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.EventHandler
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class StateEventDispatcher(
    override val name: String,
    override val parallelism: Int,
    override val messageBus: StateEventBus,
    override val functionRegistrar: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>,
    override val eventHandler: EventHandler,
    override val schedulerSupplier: AggregateSchedulerSupplier
) : AbstractEventDispatcher<StateEventExchange<*>, StateEventBus>() {

    override fun newAggregateDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<StateEventExchange<*>>
    ): MessageDispatcher {
        return AggregateStateEventDispatcher(
            namedAggregate = namedAggregate,
            parallelism = parallelism,
            messageFlux = messageFlux,
            eventHandler = eventHandler,
            functionRegistrar = functionRegistrar,
            scheduler = schedulerSupplier.getOrInitialize(namedAggregate),
        )
    }
}
