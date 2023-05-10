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
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.dispatcher.AbstractDispatcher
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

abstract class AbstractEventDispatcher<R : Mono<*>> : AbstractDispatcher<EventStreamExchange>() {
    abstract val parallelism: MessageParallelism
    abstract val domainEventBus: DomainEventBus
    abstract val functionRegistrar: AbstractEventFunctionRegistrar<R>
    abstract val eventHandler: EventHandler
    override val namedAggregates: Set<NamedAggregate>
        get() = functionRegistrar.namedAggregates
    protected abstract val schedulerSupplier: (NamedAggregate) -> Scheduler

    override fun receiveMessage(namedAggregate: NamedAggregate): Flux<EventStreamExchange> {
        return domainEventBus
            .receive(setOf(namedAggregate))
            .writeReceiverGroup(name)
            .writeMetricsSubscriber(name)
    }

    override fun newAggregateDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<EventStreamExchange>
    ): MessageDispatcher {
        return AggregateEventDispatcher(
            namedAggregate = namedAggregate,
            parallelism = parallelism,
            messageFlux = messageFlux,
            eventHandler = eventHandler,
            functionRegistrar = functionRegistrar,
            scheduler = schedulerSupplier(namedAggregate)
        )
    }
}
