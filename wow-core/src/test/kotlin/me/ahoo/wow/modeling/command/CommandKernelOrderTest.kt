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

package me.ahoo.wow.modeling.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.OnError
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.aware.VersionAware
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventStoreStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The kernel decides, applies, then appends, as one atomic unit: a persisted event is always loadable, and the
 * in-memory state never diverges from the store.
 *
 * - A sourcing failure: nothing is appended, the command fails, and `@OnError` runs on the committed state.
 * - An append failure: the state that applied the stream is discarded; the exchange version stays at the committed
 *   one and `@OnError` runs on the committed state, loaded again.
 * - When that load fails too, `@OnError` is skipped and the original error is reported.
 */
class CommandKernelOrderTest {
    private val metadata = aggregateMetadata<OrderProbeAggregate, OrderProbeAggregate>()
    private val aggregateId = metadata.aggregateId(AGGREGATE_ID)

    @BeforeEach
    fun reset() {
        OrderProbe.reset()
    }

    private class Outcome(
        val exchange: ServerCommandExchange<*>,
        val eventStore: EventStore,
        val aggregateId: AggregateId,
        val emitted: DomainEventStream?,
        val error: Throwable?
    ) {
        @Suppress("UNCHECKED_CAST")
        val aggregate: SimpleCommandAggregate<OrderProbeAggregate, OrderProbeAggregate>
            get() = exchange.getCommandAggregate<OrderProbeAggregate, OrderProbeAggregate>()
                as SimpleCommandAggregate<OrderProbeAggregate, OrderProbeAggregate>
        val storedVersions: List<Int>
            get() = eventStore.load(aggregateId).map { it.version }.collectList().block()!!
    }

    private fun repository(eventStore: EventStore): StateAggregateRepository =
        EventStoreStateAggregateRepository(ConstructorStateAggregateFactory, eventStore)

    /** Runs [command] as production does: through the aggregate processor, which runs `@OnError` once. */
    private fun process(
        eventStore: EventStore,
        command: Any,
        repository: StateAggregateRepository = repository(eventStore),
        commandAggregateFactory: CommandAggregateFactory = SimpleCommandAggregateFactory(eventStore),
    ): Outcome {
        val processor = RetryableAggregateProcessor(
            aggregateId = aggregateId,
            aggregateMetadata = metadata,
            aggregateFactory = ConstructorStateAggregateFactory,
            stateAggregateRepository = repository,
            commandAggregateFactory = commandAggregateFactory,
            maxRetries = 0,
        )
        val exchange = exchange(command)
        var emitted: DomainEventStream? = null
        var error: Throwable? = null
        try {
            emitted = processor.process(exchange).block(Duration.ofSeconds(10))
        } catch (failure: Throwable) {
            error = Exceptions.unwrap(failure)
        }
        return Outcome(exchange, eventStore, aggregateId, emitted, error)
    }

    private fun exchange(command: Any): ServerCommandExchange<*> = SimpleServerCommandExchange(
        command.toCommandMessage(aggregateId = AGGREGATE_ID, namedAggregate = metadata.namedAggregate),
    ).setServiceProvider(SimpleServiceProvider())

    /** An event store holding version 1 of the aggregate, whose further appends fail with [failure]. */
    private fun createdThenFailingAppends(failure: Throwable): EventStore {
        val eventStore = FailingAppendEventStore(failure)
        process(eventStore.delegate, CreateOrderProbe(AGGREGATE_ID)).error.assert().isNull()
        return eventStore
    }

    @Test
    fun `a committed command applies its events and appends them`() {
        val outcome = process(InMemoryEventStore(), CreateOrderProbe(AGGREGATE_ID))

        outcome.error.assert().isNull()
        outcome.storedVersions.assert().isEqualTo(listOf(1))
        outcome.aggregate.state.version.assert().isEqualTo(1)
        outcome.aggregate.commandRoot.sourced.assert().isEqualTo(listOf(OrderProbeCreated(AGGREGATE_ID)))
        outcome.aggregate.discarded.assert().isFalse()
        outcome.exchange.getAggregateVersion().assert().isEqualTo(1)
    }

    @Test
    fun `a sourcing failure stores nothing and fails the command`() {
        val eventStore = InMemoryEventStore()

        val outcome = process(eventStore, CreateFailingSourcing(AGGREGATE_ID))

        outcome.error.assert().isInstanceOf(IllegalStateException::class.java).hasMessage("sourcing failed")
        outcome.emitted.assert().isNull()
        outcome.storedVersions.assert().isEmpty()
        outcome.exchange.getAggregateVersion().assert().isEqualTo(0)
    }

    @Test
    fun `on error sees the committed state when sourcing fails`() {
        val outcome = process(InMemoryEventStore(), CreateFailingSourcing(AGGREGATE_ID))

        outcome.error.assert().isNotNull()
        OrderProbe.onErrorCalls.single().assert()
            .isEqualTo(OrderProbe.OnErrorCall(stateVersion = 0, rootVersion = 0, sourced = emptyList()))
        // @OnError ran on the aggregate loaded again, not on the one that applied the unstored events.
        outcome.aggregate.discarded.assert().isFalse()
    }

    @Test
    fun `an append failure keeps the committed version and on error sees the committed state`() {
        val failure = IllegalStateException("append failed")
        val eventStore = createdThenFailingAppends(failure)
        OrderProbe.reset()

        val outcome = process(eventStore, ChangeOrderProbe(AGGREGATE_ID))

        outcome.error.assert().isSameAs(failure)
        outcome.exchange.getAggregateVersion().assert().isEqualTo(1)
        outcome.storedVersions.assert().isEqualTo(listOf(1))
        OrderProbe.onErrorCalls.single().assert().isEqualTo(
            OrderProbe.OnErrorCall(stateVersion = 1, rootVersion = 1, sourced = listOf(OrderProbeCreated(AGGREGATE_ID)))
        )
    }

    @Test
    fun `an aggregate that applied unstored events is discarded and refuses further commands`() {
        val failure = IllegalStateException("append failed")
        val eventStore = createdThenFailingAppends(failure)
        val aggregate = SimpleCommandAggregateFactory(eventStore).create(
            metadata,
            repository(eventStore).load(aggregateId, metadata.state).block()!!,
        ) as SimpleCommandAggregate<OrderProbeAggregate, OrderProbeAggregate>

        aggregate.processAttempt(exchange(ChangeOrderProbe(AGGREGATE_ID))).test().verifyErrorMatches { it === failure }

        aggregate.discarded.assert().isTrue()
        aggregate.processAttempt(exchange(ChangeOrderProbe(AGGREGATE_ID))).test()
            .verifyErrorSatisfies {
                it.assert().isInstanceOf(IllegalStateException::class.java).hasMessageContaining("discarded")
            }
    }

    @Test
    fun `a failure before the apply leaves the aggregate usable`() {
        val outcome = process(InMemoryEventStore(), ChangeOrderProbe(AGGREGATE_ID))

        outcome.error.assert().isNotNull()
        outcome.aggregate.discarded.assert().isFalse()
    }

    @Test
    fun `on error is skipped and the original error reported when the committed state cannot be loaded`() {
        val failure = IllegalStateException("append failed")
        val eventStore = createdThenFailingAppends(failure)
        OrderProbe.reset()
        val loadFailure = IllegalStateException("store unavailable")
        val repository = FailingSecondLoadRepository(repository(eventStore), loadFailure)

        val outcome = process(eventStore, ChangeOrderProbe(AGGREGATE_ID), repository)

        outcome.error.assert().isSameAs(failure)
        failure.suppressed.assert().contains(loadFailure)
        OrderProbe.onErrorCalls.assert().isEmpty()
        outcome.exchange.getCommandAggregate<OrderProbeAggregate, OrderProbeAggregate>().assert().isNull()
        outcome.exchange.extractDeclared(StateAggregate::class.java).assert().isNull()
    }

    /** Without an `@OnError` function nothing is loaded again, and the exchange no longer holds the discarded state. */
    @Test
    fun `the exchange never exposes unstored state when there is no on error function`() {
        val failure = IllegalStateException("append failed")
        val eventStore = createdThenFailingAppends(failure)
        OrderProbe.reset()

        val outcome = process(eventStore, ChangeWithoutOnError(AGGREGATE_ID))

        outcome.error.assert().isSameAs(failure)
        outcome.exchange.getCommandAggregate<OrderProbeAggregate, OrderProbeAggregate>().assert().isNull()
        outcome.exchange.extractDeclared(StateAggregate::class.java).assert().isNull()
        outcome.exchange.getError().assert().isSameAs(failure)
        outcome.exchange.getAggregateVersion().assert().isEqualTo(1)
        OrderProbe.onErrorCalls.assert().isEmpty()
    }

    /**
     * A custom factory whose reloaded aggregate cannot run `@OnError`: skipped the same way as a failed reload, with
     * the original error, the reason suppressed, and nothing left on the exchange.
     */
    @Test
    fun `on error is skipped when the reloaded aggregate cannot run it`() {
        val failure = IllegalStateException("append failed")
        val eventStore = createdThenFailingAppends(failure)
        OrderProbe.reset()
        val simple = SimpleCommandAggregateFactory(eventStore)
        val created = AtomicInteger()
        val factory = object : CommandAggregateFactory {
            override fun <C : Any, S : Any> create(
                metadata: AggregateMetadata<C, S>,
                stateAggregate: StateAggregate<S>
            ): CommandAggregate<C, S> {
                val aggregate = simple.create(metadata, stateAggregate)
                return if (created.incrementAndGet() == 1) aggregate else ForeignCommandAggregate(aggregate)
            }
        }

        val outcome = process(eventStore, ChangeOrderProbe(AGGREGATE_ID), commandAggregateFactory = factory)

        outcome.error.assert().isSameAs(failure)
        failure.suppressed.map { it.message }.assert().contains(
            "The reloaded aggregate is not a SimpleCommandAggregate."
        )
        outcome.exchange.getError().assert().isSameAs(failure)
        outcome.exchange.getCommandAggregate<OrderProbeAggregate, OrderProbeAggregate>().assert().isNull()
        OrderProbe.onErrorCalls.assert().isEmpty()
    }

    /** A command aggregate that is not a [SimpleCommandAggregate], as a custom factory may return. */
    private class ForeignCommandAggregate<C : Any, S : Any>(
        private val delegate: CommandAggregate<C, S>
    ) : CommandAggregate<C, S> by delegate

    /**
     * Called directly, without the processor, `@OnError` runs on the instance itself: after an append failure it
     * sees the unstored events (documented on [SimpleCommandAggregate.process]).
     */
    @Test
    fun `a direct process call runs on error on the discarded instance`() {
        val eventStore = createdThenFailingAppends(IllegalStateException("append failed"))
        OrderProbe.reset()
        val aggregate = SimpleCommandAggregateFactory(eventStore).create(
            metadata,
            repository(eventStore).load(aggregateId, metadata.state).block()!!,
        )

        aggregate.process(exchange(ChangeOrderProbe(AGGREGATE_ID))).test().verifyError()

        OrderProbe.onErrorCalls.single().stateVersion.assert().isEqualTo(2)
    }

    private class FailingAppendEventStore(private val failure: Throwable) : EventStore {
        val delegate = InMemoryEventStore()

        override fun append(eventStream: DomainEventStream): Mono<Void> = failure.toMono()

        override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> =
            delegate.load(aggregateId, headVersion, tailVersion)

        override fun load(aggregateId: AggregateId, headEventTime: Long, tailEventTime: Long): Flux<DomainEventStream> =
            delegate.load(aggregateId, headEventTime, tailEventTime)

        override fun last(aggregateId: AggregateId): Mono<DomainEventStream> = delegate.last(aggregateId)
    }

    /** Loads once, then fails: the attempt loads, the reload for `@OnError` does not. */
    private class FailingSecondLoadRepository(
        private val delegate: StateAggregateRepository,
        private val failure: Throwable,
    ) : StateAggregateRepository {
        private val loads = AtomicInteger()

        override fun <S : Any> load(
            aggregateId: AggregateId,
            metadata: StateAggregateMetadata<S>,
            tailVersion: Int
        ): Mono<StateAggregate<S>> = Mono.defer {
            if (loads.incrementAndGet() > 1) failure.toMono() else delegate.load(aggregateId, metadata, tailVersion)
        }

        override fun <S : Any> load(
            aggregateId: AggregateId,
            metadata: StateAggregateMetadata<S>,
            tailEventTime: Long
        ): Mono<StateAggregate<S>> = delegate.load(aggregateId, metadata, tailEventTime)
    }

    private companion object {
        const val AGGREGATE_ID = "order-probe-1"
    }
}

internal object OrderProbe {
    data class OnErrorCall(val stateVersion: Int, val rootVersion: Int, val sourced: List<Any>)

    val onErrorCalls: MutableList<OnErrorCall> = mutableListOf()
    var recoverableFailures: Int = 0

    fun reset() {
        onErrorCalls.clear()
        recoverableFailures = 0
    }
}

@CreateAggregate
data class CreateOrderProbe(@me.ahoo.wow.api.annotation.AggregateId val id: String)

@CreateAggregate
data class CreateFailingSourcing(@me.ahoo.wow.api.annotation.AggregateId val id: String)

data class ChangeOrderProbe(@me.ahoo.wow.api.annotation.AggregateId val id: String)

/** A change without an `@OnError` function. */
data class ChangeWithoutOnError(@me.ahoo.wow.api.annotation.AggregateId val id: String)

/** Fails with a recoverable error, counting its calls. */
data class FailRecoverably(@me.ahoo.wow.api.annotation.AggregateId val id: String)

data class OrderProbeCreated(val id: String)

data class OrderProbeChanged(val id: String)

data class SourcingFails(val id: String)

@AggregateRoot
// Wow finds and invokes the private command, sourcing and error handlers by reflection.
@Suppress("UnusedPrivateMember")
class OrderProbeAggregate(private val id: String) : VersionAware {
    override var version: Int = Version.UNINITIALIZED_VERSION
    val sourced: MutableList<Any> = mutableListOf()

    fun id(): String = id

    private fun onCommand(command: CreateOrderProbe): OrderProbeCreated = OrderProbeCreated(command.id)

    /** The first event sources, the second fails: the state instance is half-applied. */
    private fun onCommand(command: CreateFailingSourcing): List<Any> =
        listOf(OrderProbeCreated(command.id), SourcingFails(command.id))

    private fun onCommand(command: ChangeOrderProbe): OrderProbeChanged = OrderProbeChanged(command.id)

    private fun onCommand(command: ChangeWithoutOnError): OrderProbeChanged = OrderProbeChanged(command.id)

    @Suppress("UnusedParameter")
    private fun onCommand(command: FailRecoverably): OrderProbeChanged {
        OrderProbe.recoverableFailures++
        throw java.util.concurrent.TimeoutException("recoverable")
    }

    private fun onSourcing(event: OrderProbeCreated) {
        sourced += event
    }

    private fun onSourcing(event: OrderProbeChanged) {
        sourced += event
    }

    @Suppress("UnusedParameter")
    private fun onSourcing(event: SourcingFails): Unit = throw IllegalStateException("sourcing failed")

    @OnError
    @Suppress("UnusedParameter")
    private fun onError(command: CreateOrderProbe, error: Throwable, stateAggregate: StateAggregate<*>) {
        OrderProbe.onErrorCalls += OrderProbe.OnErrorCall(stateAggregate.version, version, sourced.toList())
    }

    @OnError
    @Suppress("UnusedParameter")
    private fun onError(command: CreateFailingSourcing, error: Throwable, stateAggregate: StateAggregate<*>) {
        OrderProbe.onErrorCalls += OrderProbe.OnErrorCall(stateAggregate.version, version, sourced.toList())
    }

    @OnError
    @Suppress("UnusedParameter")
    private fun onError(command: ChangeOrderProbe, error: Throwable, stateAggregate: StateAggregate<*>) {
        OrderProbe.onErrorCalls += OrderProbe.OnErrorCall(stateAggregate.version, version, sourced.toList())
    }
}
