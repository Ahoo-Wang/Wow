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

package me.ahoo.wow.test.aggregate

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.modeling.command.RetryableAggregateProcessorFactory
import me.ahoo.wow.modeling.command.SimpleCommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.DefaultCommandHandler
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import reactor.core.publisher.Mono

/**
 * Where the aggregate test DSL keeps one aggregate's history and runs its commands (V6).
 *
 * Commands go through the production command pipeline: [DefaultCommandHandler], the production aggregate processor
 * (loading the state from [eventStore] and an in-memory snapshot store, `@OnError` after the final failure) and the
 * command kernel. The only difference is that a recoverable failure is not retried. Given events are appended to
 * [eventStore] at their real version, a given state is saved as a snapshot, and the state a step reports is loaded
 * again from the stores after the command, so no state instance outlives the step that produced it.
 */
internal class AggregateTestRuntime<C : Any, S : Any>(
    val metadata: AggregateMetadata<C, S>,
    val aggregateId: AggregateId,
    val stateAggregateFactory: StateAggregateFactory,
    val eventStore: EventStore,
    val serviceProvider: ServiceProvider,
    private val snapshotStore: SnapshotStore = InMemorySnapshotStore()
) {
    private companion object {
        /** The processing failure of a command, as opposed to failed events in a committed stream. */
        const val PROCESSING_FAILURE_KEY = "__TEST_PROCESSING_FAILURE__"
    }

    private val stateAggregateRepository =
        EventSourcingStateAggregateRepository(stateAggregateFactory, snapshotStore, eventStore)

    private val commandHandler = DefaultCommandHandler(
        serviceProvider = serviceProvider,
        aggregateProcessorFactory = RetryableAggregateProcessorFactory(
            stateAggregateFactory = stateAggregateFactory,
            stateAggregateRepository = stateAggregateRepository,
            commandAggregateFactory = SimpleCommandAggregateFactory(eventStore),
            maxRetries = 0,
        ),
        domainEventBus = null,
        stateEventBus = null,
        commandWaitNotifier = null,
        errorHandler = ErrorHandler { exchange, error ->
            exchange.setAttribute(PROCESSING_FAILURE_KEY, error)
            Mono.empty()
        },
    )

    /** Appends [events] as one stream at the aggregate's next version, as if a command had produced them. */
    fun appendGiven(events: Array<out Any>, ownerId: String, spaceId: SpaceId): Mono<Void> {
        if (events.isEmpty()) {
            return Mono.empty()
        }
        return eventStore.last(aggregateId)
            .map { it.version }
            .defaultIfEmpty(0)
            .flatMap { version ->
                val eventStream = events.toDomainEventStream(
                    upstream = GivenInitializationCommand(
                        aggregateId = aggregateId,
                        ownerId = ownerId,
                        spaceId = spaceId,
                    ),
                    aggregateVersion = version,
                )
                eventStore.append(eventStream)
            }
    }

    /** Saves [stateAggregate] as the aggregate's snapshot: the state commands start from. */
    fun seedState(stateAggregate: StateAggregate<S>): Mono<Void> = snapshotStore.save(SimpleSnapshot(stateAggregate))

    /** The aggregate's current state, loaded from the stores. */
    fun load(): Mono<StateAggregate<S>> = stateAggregateRepository.load(aggregateId, metadata.state)

    /** A result for a command rejected before it reached the pipeline, such as by validation. */
    fun rejected(exchange: ServerCommandExchange<*>, error: Throwable): Mono<ExpectedResult<S>> =
        loadOrEmpty(exchange).map { ExpectedResult(exchange = exchange, stateAggregate = it, error = error) }

    /** Runs [commandMessage] through the command pipeline and reports its outcome with the state loaded afterwards. */
    fun execute(commandMessage: CommandMessage<*>): Mono<ExpectedResult<S>> {
        val exchange = SimpleServerCommandExchange(commandMessage)
        return commandHandler.handle(exchange, metadata).then(Mono.defer { result(exchange) })
    }

    private fun result(exchange: ServerCommandExchange<*>): Mono<ExpectedResult<S>> {
        val processingFailure = exchange.getAttribute<Throwable>(PROCESSING_FAILURE_KEY)
        val eventStream = if (processingFailure == null) exchange.getEventStream() else null
        return load()
            .map {
                ExpectedResult(
                    exchange = exchange,
                    stateAggregate = it,
                    domainEventStream = eventStream,
                    error = processingFailure ?: exchange.getError(),
                )
            }.onErrorResume { loadFailure ->
                // The committed history cannot be sourced: report it, with the state the command saw.
                kernelStateOrEmpty(exchange).map {
                    ExpectedResult(
                        exchange = exchange,
                        stateAggregate = it,
                        domainEventStream = eventStream,
                        error = processingFailure ?: loadFailure,
                    )
                }
            }
    }

    private fun loadOrEmpty(exchange: ServerCommandExchange<*>): Mono<StateAggregate<S>> =
        load().onErrorResume { kernelStateOrEmpty(exchange) }

    @Suppress("UNCHECKED_CAST")
    private fun kernelStateOrEmpty(exchange: ServerCommandExchange<*>): Mono<StateAggregate<S>> {
        val kernelState = exchange.extractDeclared(StateAggregate::class.java) as StateAggregate<S>?
        return kernelState?.let { Mono.just(it) } ?: stateAggregateFactory.createAsMono(metadata.state, aggregateId)
    }

    /** An independent copy: the same history in new in-memory stores, with a copy of the service provider. */
    fun fork(): AggregateTestRuntime<C, S> {
        val forked = AggregateTestRuntime(
            metadata = metadata,
            aggregateId = aggregateId,
            stateAggregateFactory = stateAggregateFactory,
            eventStore = InMemoryEventStore(),
            serviceProvider = serviceProvider.copy(),
        )
        snapshotStore.load<S>(aggregateId)
            .flatMap { forked.snapshotStore.save(it) }
            .thenMany(eventStore.load(aggregateId).concatMap { forked.eventStore.append(it.copy()) })
            .then()
            .block()
        return forked
    }

    /**
     * The same stores and services for [aggregateId]: the aggregate a command message addresses, which can differ
     * from the one the verifier was created for (an aggregate whose owner is its ID generates it).
     */
    fun withAggregateId(aggregateId: AggregateId): AggregateTestRuntime<C, S> {
        if (aggregateId == this.aggregateId) {
            return this
        }
        return AggregateTestRuntime(
            metadata = metadata,
            aggregateId = aggregateId,
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            serviceProvider = serviceProvider,
            snapshotStore = snapshotStore,
        )
    }

    /** A runtime for the same aggregate with no history: new in-memory stores, the same service provider. */
    fun empty(): AggregateTestRuntime<C, S> =
        AggregateTestRuntime(
            metadata = metadata,
            aggregateId = aggregateId,
            stateAggregateFactory = stateAggregateFactory,
            eventStore = InMemoryEventStore(),
            serviceProvider = serviceProvider,
        )
}
