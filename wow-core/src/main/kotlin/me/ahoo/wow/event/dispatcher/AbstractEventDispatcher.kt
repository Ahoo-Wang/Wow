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
import me.ahoo.wow.messaging.MessageBus
import me.ahoo.wow.messaging.dispatcher.AbstractDispatcher
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

abstract class AbstractEventDispatcher<E : MessageExchange<*, *>, BUS : MessageBus<*, E>> : AbstractDispatcher<E>() {

    /**
     * The level of parallelism for processing events.
     */
    abstract val parallelism: Int

    /**
     * The message bus for sending and receiving events.
     */
    abstract val messageBus: BUS

    /**
     * The registrar containing event processing functions.
     */
    abstract val functionRegistrar: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>

    /**
     * The event handler for processing events.
     */
    abstract val eventHandler: EventHandler
    abstract val schedulerSupplier: AggregateSchedulerSupplier

    override val namedAggregates: Set<NamedAggregate> by lazy {
        functionRegistrar.functions
            .flatMap {
                it.supportedTopics
            }
            .toSet()
    }

    override fun receiveMessage(namedAggregate: NamedAggregate): Flux<E> {
        return messageBus.receive(setOf(namedAggregate))
            .writeReceiverGroup(name)
            .writeMetricsSubscriber(name)
    }
}
