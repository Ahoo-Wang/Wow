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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.messaging.TopicKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.dispatcher.AggregateMessageDispatcher
import me.ahoo.wow.messaging.dispatcher.SafeSubscriber
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.messaging.function.SimpleMessageFunctionRegistrar
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.serialization.toJsonString
import reactor.core.publisher.Flux
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono

abstract class AbstractEventDispatcher<R : Mono<*>> : MessageDispatcher {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    abstract val parallelism: Int
    abstract val stateEventBus: StateEventBus
    abstract val domainEventBus: DomainEventBus
    abstract val functionRegistrar: AbstractEventFunctionRegistrar
    abstract val eventHandler: EventHandler
    protected abstract val schedulerSupplier: AggregateSchedulerSupplier
    private val domainEventDistributionSubscriber = DomainEventDistributionSubscriber()
    private val stateEventDistributionSubscriber = StateEventDistributionSubscriber()
    private val eventStreamFunctionRegistrar by lazy {
        filterRegistrar(TopicKind.EVENT_STREAM)
    }
    private val eventStreamTopics by lazy {
        eventStreamFunctionRegistrar.functions
            .flatMap {
                it.supportedTopics
            }
            .toSet()
    }

    private val stateEventFunctionRegistrar by lazy {
        filterRegistrar(TopicKind.STATE_EVENT)
    }
    private val stateEventTopics by lazy {
        stateEventFunctionRegistrar.functions
            .flatMap {
                it.supportedTopics
            }
            .toSet()
    }

    private fun filterRegistrar(topicKind: TopicKind): MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
        val registrar =
            SimpleMessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>()
        functionRegistrar.functions.filter {
            it.functionKind.topicKind == topicKind
        }.forEach {
            registrar.register(it)
        }
        return registrar
    }

    private fun receiveEventStream(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> {
        return domainEventBus
            .receive(namedAggregates)
            .writeReceiverGroup(name)
            .writeMetricsSubscriber(name)
    }

    private fun receiveStateEventStream(namedAggregates: Set<NamedAggregate>): Flux<StateEventExchange<*>> {
        return stateEventBus
            .receive(namedAggregates)
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
    ): AggregateMessageDispatcher<StateEventExchange<*>> {
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
        log.info {
            "[$name] Run subscribe to Event:${eventStreamTopics.toJsonString()}."
        }
        if (eventStreamTopics.isEmpty()) {
            log.warn {
                "[$name] Ignore start [DomainEventDistributionSubscriber] because namedAggregates is empty."
            }
        } else {
            receiveEventStream(eventStreamTopics)
                .groupBy { it.message.materialize() }
                .subscribe(domainEventDistributionSubscriber)
        }
        log.info {
            "[$name] Run subscribe to State Event:${stateEventTopics.toJsonString()}."
        }

        if (stateEventTopics.isEmpty()) {
            log.warn {
                "[$name] Ignore start [StateEventDistributionSubscriber] because namedAggregates is empty."
            }
        } else {
            receiveStateEventStream(stateEventTopics)
                .groupBy { it.message.materialize() }
                .subscribe(stateEventDistributionSubscriber)
        }
    }

    override fun close() {
        log.info {
            "[$name] Close."
        }
        domainEventDistributionSubscriber.cancel()
        stateEventDistributionSubscriber.cancel()
    }

    inner class DomainEventDistributionSubscriber :
        SafeSubscriber<GroupedFlux<MaterializedNamedAggregate, EventStreamExchange>>() {

        override val name: String
            get() = "${this@AbstractEventDispatcher.name}-DomainEventDistributionSubscriber"

        override fun safeOnNext(value: GroupedFlux<MaterializedNamedAggregate, EventStreamExchange>) {
            newAggregateEventDispatcher(
                namedAggregate = value.key(),
                messageFlux = value,
            ).run()
        }
    }

    inner class StateEventDistributionSubscriber :
        SafeSubscriber<GroupedFlux<MaterializedNamedAggregate, StateEventExchange<*>>>() {

        override val name: String
            get() = "${this@AbstractEventDispatcher.name}-DomainEventDistributionSubscriber"

        override fun safeOnNext(value: GroupedFlux<MaterializedNamedAggregate, StateEventExchange<*>>) {
            newAggregateStateEventDispatcher(
                namedAggregate = value.key(),
                messageFlux = value,
            ).run()
        }
    }
}
