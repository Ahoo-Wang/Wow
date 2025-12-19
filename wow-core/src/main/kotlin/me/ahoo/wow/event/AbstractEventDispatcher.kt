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
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.serialization.toJsonString
import reactor.core.publisher.Flux
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono

/**
 * Abstract base class for event dispatchers that coordinate domain and state event processing.
 *
 * This class manages the lifecycle of event processing across multiple aggregates,
 * handling both domain events and state events. It subscribes to event buses,
 * groups events by aggregate, and creates appropriate dispatchers for each aggregate.
 *
 * @param R The return type of processing operations (typically Mono<Void>)
 *
 * @property parallelism The level of parallelism for processing
 * @property stateEventBus The bus for state events
 * @property domainEventBus The bus for domain events
 * @property functionRegistrar The registrar for event processing functions
 * @property eventHandler The handler for processing events
 * @property schedulerSupplier Supplier for creating aggregate schedulers
 *
 * @see MessageDispatcher
 * @see StateEventBus
 * @see DomainEventBus
 * @see AbstractEventFunctionRegistrar
 * @see EventHandler
 * @see AggregateSchedulerSupplier
 */
abstract class AbstractEventDispatcher<R : Mono<*>> : MessageDispatcher {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * The level of parallelism for processing events.
     */
    abstract val parallelism: Int

    /**
     * The state event bus for handling state-related events.
     */
    abstract val stateEventBus: StateEventBus

    /**
     * The domain event bus for handling domain events.
     */
    abstract val domainEventBus: DomainEventBus

    /**
     * The registrar containing event processing functions.
     */
    abstract val functionRegistrar: AbstractEventFunctionRegistrar

    /**
     * The handler responsible for processing events.
     */
    abstract val eventHandler: EventHandler

    /**
     * Supplier for creating schedulers for aggregate processing.
     */
    protected abstract val schedulerSupplier: AggregateSchedulerSupplier
    private val domainEventDistributionSubscriber = DomainEventDistributionSubscriber()
    private val stateEventDistributionSubscriber = StateEventDistributionSubscriber()
    private val eventStreamFunctionRegistrar by lazy {
        functionRegistrar.filter {
            it.functionKind.topicKind == TopicKind.EVENT_STREAM
        }
    }
    private val eventStreamTopics by lazy {
        eventStreamFunctionRegistrar.functions
            .flatMap {
                it.supportedTopics
            }.toSet()
    }

    private val stateEventFunctionRegistrar by lazy {
        functionRegistrar.filter {
            it.functionKind.topicKind == TopicKind.STATE_EVENT
        }
    }
    private val stateEventTopics by lazy {
        stateEventFunctionRegistrar.functions
            .flatMap {
                it.supportedTopics
            }.toSet()
    }

    private fun receiveEventStream(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> =
        domainEventBus
            .receive(namedAggregates)
            .writeReceiverGroup(name)
            .writeMetricsSubscriber(name)

    private fun receiveStateEventStream(namedAggregates: Set<NamedAggregate>): Flux<StateEventExchange<*>> =
        stateEventBus
            .receive(namedAggregates)
            .writeReceiverGroup(name)
            .writeMetricsSubscriber(name)

    private fun newAggregateEventDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<EventStreamExchange>
    ): MessageDispatcher =
        AggregateEventDispatcher(
            namedAggregate = namedAggregate,
            parallelism = parallelism,
            messageFlux = messageFlux,
            eventHandler = eventHandler,
            functionRegistrar = eventStreamFunctionRegistrar,
            scheduler = schedulerSupplier.getOrInitialize(namedAggregate),
        )

    private fun newAggregateStateEventDispatcher(
        namedAggregate: NamedAggregate,
        messageFlux: Flux<StateEventExchange<*>>
    ): AggregateMessageDispatcher<StateEventExchange<*>> =
        AggregateStateEventDispatcher(
            namedAggregate = namedAggregate,
            parallelism = parallelism,
            messageFlux = messageFlux,
            eventHandler = eventHandler,
            functionRegistrar = stateEventFunctionRegistrar,
            scheduler = schedulerSupplier.getOrInitialize(namedAggregate),
        )

    /**
     * Starts the event dispatcher by subscribing to event streams.
     *
     * This method initializes subscriptions to both domain event streams and state event streams.
     * Events are grouped by aggregate and distributed to appropriate aggregate dispatchers.
     * If no topics are available for subscription, appropriate warnings are logged.
     *
     * @see receiveEventStream
     * @see receiveStateEventStream
     * @see DomainEventDistributionSubscriber
     * @see StateEventDistributionSubscriber
     */
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

    /**
     * Closes the event dispatcher and cancels all subscriptions.
     *
     * This method gracefully shuts down the dispatcher by canceling both
     * domain event and state event subscriptions.
     *
     * @see SafeSubscriber.cancel
     */
    override fun close() {
        log.info {
            "[$name] Close."
        }
        domainEventDistributionSubscriber.cancel()
        stateEventDistributionSubscriber.cancel()
    }

    /**
     * Subscriber that distributes domain events to aggregate-specific dispatchers.
     *
     * This inner class handles grouped fluxes of domain event streams, creating
     * and starting an aggregate event dispatcher for each named aggregate.
     *
     * @see SafeSubscriber
     * @see GroupedFlux
     * @see MaterializedNamedAggregate
     * @see EventStreamExchange
     * @see newAggregateEventDispatcher
     */
    inner class DomainEventDistributionSubscriber :
        SafeSubscriber<GroupedFlux<MaterializedNamedAggregate, EventStreamExchange>>() {
        /**
         * The name of this subscriber for logging and identification.
         */
        override val name: String
            get() = "${this@AbstractEventDispatcher.name}-DomainEventDistributionSubscriber"

        /**
         * Processes a grouped flux of event streams for a specific aggregate.
         *
         * Creates a new aggregate event dispatcher and starts processing events
         * for the aggregate represented by the group key.
         *
         * @param value The grouped flux containing event streams for one aggregate
         *
         * @see newAggregateEventDispatcher
         * @see AggregateMessageDispatcher.run
         */
        override fun safeOnNext(value: GroupedFlux<MaterializedNamedAggregate, EventStreamExchange>) {
            newAggregateEventDispatcher(
                namedAggregate = value.key(),
                messageFlux = value,
            ).run()
        }
    }

    /**
     * Subscriber that distributes state events to aggregate-specific dispatchers.
     *
     * This inner class handles grouped fluxes of state events, creating
     * and starting an aggregate state event dispatcher for each named aggregate.
     *
     * @see SafeSubscriber
     * @see GroupedFlux
     * @see MaterializedNamedAggregate
     * @see StateEventExchange
     * @see newAggregateStateEventDispatcher
     */
    inner class StateEventDistributionSubscriber :
        SafeSubscriber<GroupedFlux<MaterializedNamedAggregate, StateEventExchange<*>>>() {
        /**
         * The name of this subscriber for logging and identification.
         */
        override val name: String
            get() = "${this@AbstractEventDispatcher.name}-StateEventDistributionSubscriber"

        /**
         * Processes a grouped flux of state events for a specific aggregate.
         *
         * Creates a new aggregate state event dispatcher and starts processing events
         * for the aggregate represented by the group key.
         *
         * @param value The grouped flux containing state events for one aggregate
         *
         * @see newAggregateStateEventDispatcher
         * @see AggregateMessageDispatcher.run
         */
        override fun safeOnNext(value: GroupedFlux<MaterializedNamedAggregate, StateEventExchange<*>>) {
            newAggregateStateEventDispatcher(
                namedAggregate = value.key(),
                messageFlux = value,
            ).run()
        }
    }
}
