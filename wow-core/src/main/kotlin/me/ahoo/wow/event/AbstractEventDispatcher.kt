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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.SimpleMultipleMessageFunctionRegistrar
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.serialization.asJsonString
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

abstract class AbstractEventDispatcher<R : Mono<*>> : MessageDispatcher {
    companion object {
        private val log = LoggerFactory.getLogger(AbstractEventDispatcher::class.java)
    }

    abstract val parallelism: Int
    abstract val stateEventBus: StateEventBus
    abstract val domainEventBus: DomainEventBus
    abstract val functionRegistrar: AbstractEventFunctionRegistrar
    abstract val eventHandler: EventHandler
    protected abstract val schedulerSupplier: AggregateSchedulerSupplier

    private val eventStreamFunctionRegistrar by lazy {
        filterRegistrar(TopicKind.EVENT_STREAM)
    }
    private val eventStreamTopics by lazy {
        eventStreamFunctionRegistrar.functions
            .flatMap {
                @Suppress("UNCHECKED_CAST")
                it.supportedTopics as Set<NamedAggregate>
            }
            .toSet()
    }

    private val stateEventFunctionRegistrar by lazy {
        filterRegistrar(TopicKind.STATE_EVENT)
    }
    private val stateEventTopics by lazy {
        stateEventFunctionRegistrar.functions
            .flatMap {
                @Suppress("UNCHECKED_CAST")
                it.supportedTopics as Set<NamedAggregate>
            }
            .toSet()
    }

    private fun filterRegistrar(topicKind: TopicKind): SimpleMultipleMessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
        val registrar =
            SimpleMultipleMessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>()
        functionRegistrar.functions.filter {
            it.functionKind.topicKind == topicKind
        }.forEach {
            registrar.register(it)
        }
        return registrar
    }

    private val eventDispatchers by lazy {
        eventStreamTopics
            .map {
                val messageFlux = receiveEventStream(it)
                    .writeReceiverGroup(name)
                    .writeMetricsSubscriber(name)
                newAggregateEventDispatcher(it, messageFlux)
            }
    }

    private val stateEventDispatchers by lazy {
        stateEventTopics
            .map {
                val messageFlux = receiveStateEventStream(it)
                    .writeReceiverGroup(name)
                    .writeMetricsSubscriber(name)
                newAggregateStateEventDispatcher(it, messageFlux)
            }
    }

    private fun receiveEventStream(namedAggregate: NamedAggregate): Flux<EventStreamExchange> {
        return domainEventBus
            .receive(setOf(namedAggregate))
            .writeReceiverGroup(name)
            .writeMetricsSubscriber(name)
    }

    private fun receiveStateEventStream(namedAggregate: NamedAggregate): Flux<StateEventExchange<*>> {
        return stateEventBus
            .receive(setOf(namedAggregate))
            .writeReceiverGroup(name)
            .writeMetricsSubscriber(name)
    }

    private fun newAggregateEventDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<EventStreamExchange>
    ): MessageDispatcher {
        return AggregateEventDispatcher(
            namedAggregate = namedAggregate,
            parallelism = parallelism,
            messageFlux = messageFlux,
            eventHandler = eventHandler,
            functionRegistrar = eventStreamFunctionRegistrar,
            scheduler = schedulerSupplier.getOrInitialize(namedAggregate),
        )
    }

    private fun newAggregateStateEventDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<StateEventExchange<*>>
    ): MessageDispatcher {
        return AggregateStateEventDispatcher(
            namedAggregate = namedAggregate,
            parallelism = parallelism,
            messageFlux = messageFlux,
            eventHandler = eventHandler,
            functionRegistrar = stateEventFunctionRegistrar,
            scheduler = schedulerSupplier.getOrInitialize(namedAggregate),
        )
    }

    override fun run() {
        if (log.isInfoEnabled) {
            log.info("[$name] Run subscribe to Event:${eventStreamTopics.asJsonString()}.")
        }
        if (eventStreamTopics.isEmpty()) {
            if (log.isWarnEnabled) {
                log.warn("[$name] Ignore start [AggregateEventDispatcher] because namedAggregates is empty.")
            }
        } else {
            eventDispatchers.forEach { it.run() }
        }

        if (log.isInfoEnabled) {
            log.info("[$name] Run subscribe to State Event:${stateEventTopics.asJsonString()}.")
        }
        if (stateEventTopics.isEmpty()) {
            if (log.isWarnEnabled) {
                log.warn("[$name] Ignore start [AggregateStateEventDispatcher] because namedAggregates is empty.")
            }
        } else {
            stateEventDispatchers.forEach { it.run() }
        }
    }

    override fun close() {
        if (log.isInfoEnabled) {
            log.info("[$name] Close.")
        }
        eventDispatchers.forEach { it.close() }
        stateEventDispatchers.forEach { it.close() }
    }
}
