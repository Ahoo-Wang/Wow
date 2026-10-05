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
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.time.Duration

/**
 * B9: the kernel decides, appends, and only then applies the events to the state.
 *
 * - A failed append leaves the state at the last committed version: nothing is sourced and `@OnError` sees the
 *   committed state.
 * - A sourcing failure after the append cannot undo the commit: the command is reported as committed (P2), the state
 *   instance stays at the committed version and is discarded, so it refuses further commands.
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
        val aggregate: CommandAggregate<OrderProbeAggregate, OrderProbeAggregate>,
        val eventStore: EventStore,
        val emitted: DomainEventStream?,
        val error: Throwable?
    ) {
        val root: OrderProbeAggregate get() = aggregate.commandRoot
        val storedVersions: List<Int>
            get() = eventStore.load(aggregate.aggregateId).map { it.version }.collectList().block()!!
    }

    private fun aggregate(eventStore: EventStore) =
        SimpleCommandAggregateFactory(eventStore).create(
            metadata,
            ConstructorStateAggregateFactory.create(metadata.state, aggregateId),
        )

    private fun process(
        aggregate: CommandAggregate<OrderProbeAggregate, OrderProbeAggregate>,
        eventStore: EventStore,
        command: Any
    ): Outcome {
        val exchange = SimpleServerCommandExchange(
            command.toCommandMessage(aggregateId = AGGREGATE_ID, namedAggregate = metadata.namedAggregate),
        ).setServiceProvider(SimpleServiceProvider())
        var emitted: DomainEventStream? = null
        var error: Throwable? = null
        try {
            emitted = aggregate.process(exchange).block(Duration.ofSeconds(10))
        } catch (failure: Throwable) {
            error = Exceptions.unwrap(failure)
        }
        return Outcome(exchange, aggregate, eventStore, emitted, error)
    }

    private fun process(eventStore: EventStore, command: Any): Outcome =
        process(aggregate(eventStore), eventStore, command)

    @Test
    fun `a committed command applies its events after the append`() {
        val outcome = process(InMemoryEventStore(), CreateOrderProbe(AGGREGATE_ID))

        outcome.error.assert().isNull()
        outcome.storedVersions.assert().isEqualTo(listOf(1))
        outcome.aggregate.state.version.assert().isEqualTo(1)
        outcome.root.version.assert().isEqualTo(1)
        outcome.root.sourced.assert().isEqualTo(listOf(OrderProbeCreated(AGGREGATE_ID)))
        outcome.exchange.getAggregateVersion().assert().isEqualTo(1)
    }

    @Test
    fun `a failed append leaves the state at the last committed version`() {
        val failure = IllegalStateException("append failed")

        val outcome = process(FailingAppendEventStore(failure), CreateOrderProbe(AGGREGATE_ID))

        outcome.error.assert().isSameAs(failure)
        outcome.aggregate.state.version.assert().isEqualTo(0)
        outcome.root.version.assert().isEqualTo(0)
        outcome.root.sourced.assert().isEmpty()
        outcome.exchange.getAggregateVersion().assert().isEqualTo(0)
    }

    @Test
    fun `on error sees the last committed state when the append fails`() {
        val outcome =
            process(FailingAppendEventStore(IllegalStateException("append failed")), CreateOrderProbe(AGGREGATE_ID))

        outcome.error.assert().isNotNull()
        val call = OrderProbe.onErrorCalls.single()
        call.stateVersion.assert().isEqualTo(0)
        call.rootVersion.assert().isEqualTo(0)
        call.sourced.assert().isEmpty()
    }

    @Test
    fun `a sourcing failure after the append reports the command as committed`() {
        val outcome = process(InMemoryEventStore(), CreateFailingSourcing(AGGREGATE_ID))

        outcome.error.assert().isNull()
        outcome.emitted.assert().isNotNull()
        outcome.emitted!!.version.assert().isEqualTo(1)
        outcome.storedVersions.assert().isEqualTo(listOf(1))
        outcome.exchange.getAggregateVersion().assert().isEqualTo(1)
        OrderProbe.onErrorCalls.assert().isEmpty()
    }

    @Test
    fun `a sourcing failure leaves the state metadata at the committed version`() {
        val outcome = process(InMemoryEventStore(), CreateFailingSourcing(AGGREGATE_ID))

        outcome.aggregate.state.version.assert().isEqualTo(0)
        outcome.aggregate.state.eventId.assert().isEmpty()
        outcome.root.version.assert().isEqualTo(0)
    }

    @Test
    fun `an aggregate whose state failed to apply refuses further commands`() {
        val eventStore = InMemoryEventStore()
        val aggregate = aggregate(eventStore)
        process(aggregate, eventStore, CreateFailingSourcing(AGGREGATE_ID)).error.assert().isNull()

        val next = process(aggregate, eventStore, ChangeOrderProbe(AGGREGATE_ID))

        next.error.assert().isInstanceOf(IllegalStateException::class.java)
        next.storedVersions.assert().isEqualTo(listOf(1))
    }

    private class FailingAppendEventStore(private val failure: Throwable) : EventStore {
        private val delegate = InMemoryEventStore()

        override fun append(eventStream: DomainEventStream): Mono<Void> = failure.toMono()

        override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> =
            delegate.load(aggregateId, headVersion, tailVersion)

        override fun load(aggregateId: AggregateId, headEventTime: Long, tailEventTime: Long): Flux<DomainEventStream> =
            delegate.load(aggregateId, headEventTime, tailEventTime)

        override fun last(aggregateId: AggregateId): Mono<DomainEventStream> = delegate.last(aggregateId)
    }

    private companion object {
        const val AGGREGATE_ID = "order-probe-1"
    }
}

internal object OrderProbe {
    data class OnErrorCall(val stateVersion: Int, val rootVersion: Int, val sourced: List<Any>)

    val onErrorCalls: MutableList<OnErrorCall> = mutableListOf()

    fun reset() {
        onErrorCalls.clear()
    }
}

@CreateAggregate
data class CreateOrderProbe(@me.ahoo.wow.api.annotation.AggregateId val id: String)

@CreateAggregate
data class CreateFailingSourcing(@me.ahoo.wow.api.annotation.AggregateId val id: String)

data class ChangeOrderProbe(@me.ahoo.wow.api.annotation.AggregateId val id: String)

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
}
