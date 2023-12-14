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

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MultipleMessageFunctionRegistrar
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.util.concurrent.ConcurrentHashMap

@Suppress("LongParameterList")
class AggregateStateEventDispatcher(
    override val namedAggregate: NamedAggregate,
    override val name: String =
        "${namedAggregate.aggregateName}-${AggregateStateEventDispatcher::class.simpleName!!}",
    override val parallelism: Int = MessageParallelism.DEFAULT_PARALLELISM,
    override val messageFlux: Flux<StateEventExchange<*>>,
    override val functionRegistrar:
    MultipleMessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>,
    override val eventHandler: EventHandler,
    override val scheduler: Scheduler
) : AbstractAggregateEventDispatcher<StateEventExchange<*>>() {

    override fun StateEventExchange<*>.createEventExchange(event: DomainEvent<*>): DomainEventExchange<*> {
        return SimpleStateDomainEventExchange(
            state = message,
            message = event,
            attributes = ConcurrentHashMap(attributes),
        )
    }
}
