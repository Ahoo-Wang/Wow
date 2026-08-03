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

package me.ahoo.wow.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.event.dispatcher.DomainEventHandler
import me.ahoo.wow.eventsourcing.AggregateEventStoreRegistry
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.RoutingEventStore
import me.ahoo.wow.eventsourcing.snapshot.AggregateSnapshotStoreRegistry
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.RoutingSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotHandler
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.projection.ProjectionHandler
import me.ahoo.wow.saga.stateless.StatelessSagaHandler
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

internal class MetricDecoratorFactoryTest {
    private val aggregateId = MaterializedNamedAggregate("sales", "Order").aggregateId("order-1")

    @Test
    fun `disabled factory should return the original component`() {
        val component = mockk<CommandHandler>()

        MetricDecoratorFactory(WowMetrics.NONE).decorate(component, "commandHandler")
            .assert()
            .isSameAs(component)
    }

    @Test
    fun `factory should not wrap a metered component twice`() {
        val factory = MetricDecoratorFactory(WowMetrics(SimpleMeterRegistry()))
        val component = mockk<CommandHandler>()
        val once = factory.decorate(component, "commandHandler")

        factory.decorate(once, "anotherSource").assert().isSameAs(once)
    }

    @Test
    fun `factory should reject blank source`() {
        assertThrows(IllegalArgumentException::class.java) {
            MetricDecoratorFactory(WowMetrics(SimpleMeterRegistry()))
                .decorate(mockk<CommandHandler>(), " ")
        }
    }

    @Test
    fun `factory should preserve composite command boundaries and unsupported components`() {
        val factory = MetricDecoratorFactory(WowMetrics(SimpleMeterRegistry()))
        val localFirst = mockk<LocalFirstCommandBus>()
        val gateway = mockk<CommandGateway>()
        val unsupported = Any()

        factory.decorate(localFirst, "local-first").assert().isSameAs(localFirst)
        factory.decorate(gateway, "gateway").assert().isSameAs(gateway)
        factory.decorate(unsupported, "unsupported").assert().isSameAs(unsupported)
    }

    @Test
    fun `metered extensions should select every supported decorator`() {
        val metrics = WowMetrics(SimpleMeterRegistry())

        (mockk<LocalCommandBus>() as CommandBus).metered(metrics, "local-command-bus")
            .assert().isInstanceOf(MetricLocalCommandBus::class.java)
        (mockk<DistributedCommandBus>() as CommandBus).metered(metrics, "distributed-command-bus")
            .assert().isInstanceOf(MetricDistributedCommandBus::class.java)
        mockk<CommandBus>().metered(metrics, "command-bus")
            .assert().isInstanceOf(MetricCommandBus::class.java)
        (mockk<LocalDomainEventBus>() as DomainEventBus).metered(metrics, "local-domain-event-bus")
            .assert().isInstanceOf(MetricLocalDomainEventBus::class.java)
        (mockk<DistributedDomainEventBus>() as DomainEventBus).metered(metrics, "distributed-domain-event-bus")
            .assert().isInstanceOf(MetricDistributedDomainEventBus::class.java)
        mockk<DomainEventBus>().metered(metrics, "domain-event-bus")
            .assert().isInstanceOf(MetricDomainEventBus::class.java)
        (mockk<LocalStateEventBus>() as StateEventBus).metered(metrics, "local-state-event-bus")
            .assert().isInstanceOf(MetricLocalStateEventBus::class.java)
        (mockk<DistributedStateEventBus>() as StateEventBus).metered(metrics, "distributed-state-event-bus")
            .assert().isInstanceOf(MetricDistributedStateEventBus::class.java)
        mockk<StateEventBus>().metered(metrics, "state-event-bus")
            .assert().isInstanceOf(MetricStateEventBus::class.java)
        mockk<EventStore>().metered(metrics, "event-store")
            .assert().isInstanceOf(MetricEventStore::class.java)
        mockk<SnapshotStore>().metered(metrics, "snapshot-store")
            .assert().isInstanceOf(MetricSnapshotStore::class.java)
        mockk<SnapshotStrategy>().metered(metrics, "snapshot-strategy")
            .assert().isInstanceOf(MetricSnapshotStrategy::class.java)
        mockk<CommandHandler>().metered(metrics, "command-handler")
            .assert().isInstanceOf(MetricCommandHandler::class.java)
        mockk<SnapshotHandler>().metered(metrics, "snapshot-handler")
            .assert().isInstanceOf(MetricSnapshotHandler::class.java)
        mockk<DomainEventHandler>().metered(metrics, "domain-event-handler")
            .assert().isInstanceOf(MetricDomainEventHandler::class.java)
        mockk<StatelessSagaHandler>().metered(metrics, "stateless-saga-handler")
            .assert().isInstanceOf(MetricStatelessSagaHandler::class.java)
        mockk<ProjectionHandler>().metered(metrics, "projection-handler")
            .assert().isInstanceOf(MetricProjectionHandler::class.java)
    }

    @Test
    fun `factory should preserve routing store ownership through decorator chains`() {
        val routingStore = RoutingEventStore(
            AggregateEventStoreRegistry(
                defaultEventStore = NoOpEventStore,
                routes = emptyMap(),
            )
        )
        val tracingLikeStore = EventStoreDecorator(routingStore)

        MetricDecoratorFactory(WowMetrics(SimpleMeterRegistry()))
            .decorate(tracingLikeStore, "routingEventStore")
            .assert()
            .isSameAs(tracingLikeStore)
    }

    @Test
    fun `factory should preserve routing snapshot store ownership through decorator chains`() {
        val routingStore = RoutingSnapshotStore(
            AggregateSnapshotStoreRegistry(
                defaultSnapshotStore = InMemorySnapshotStore(),
                routes = emptyMap(),
            ),
        )
        val tracingLikeStore = SnapshotStoreDecorator(routingStore)

        MetricDecoratorFactory(WowMetrics(SimpleMeterRegistry()))
            .decorate(tracingLikeStore, "routingSnapshotStore")
            .assert()
            .isSameAs(tracingLikeStore)
    }

    @Test
    fun `event store decorator should record semantic operation and explicit source`() {
        val registry = SimpleMeterRegistry()
        val delegate = CloseCountingEventStore()
        val eventStore = delegate.metered(WowMetrics(registry), "primary-event-store")

        StepVerifier.create(eventStore.append(SimpleDomainEventStreamStub(aggregateId)))
            .verifyComplete()
        eventStore.close()

        registry.get(WowMetricNames.OPERATION)
            .tags(
                "component", "event_store",
                "operation", "append",
                "context", "sales",
                "aggregate", "Order",
                "source", "primary-event-store",
                "outcome", "success",
            ).timer()
            .count()
            .assert()
            .isEqualTo(1)
        delegate.closeCount.assert().isEqualTo(1)
    }

    @Test
    fun `event store decorator should preserve synchronous validation`() {
        val delegate = object : EventStore by NoOpEventStore {
            override fun load(
                aggregateId: AggregateId,
                headVersion: Int,
                tailVersion: Int,
            ): Flux<DomainEventStream> = throw IllegalArgumentException("invalid version range")
        }
        val eventStore = delegate.metered(WowMetrics(SimpleMeterRegistry()), "event-store")

        assertThrows(IllegalArgumentException::class.java) {
            eventStore.load(aggregateId, -1, 0)
        }
    }

    @Test
    fun `metrics subscriber should be available in Reactor context`() {
        val publisher = Flux.deferContextual {
            Flux.just(requireNotNull(it.getMetricsSubscriber()))
        }.writeMetricsSubscriber("projection-worker")

        StepVerifier.create(publisher)
            .expectNext("projection-worker")
            .verifyComplete()
    }

    private class EventStoreDecorator(
        override val delegate: EventStore,
    ) : EventStore by delegate,
        Decorator<EventStore>

    private class SnapshotStoreDecorator(
        override val delegate: SnapshotStore,
    ) : SnapshotStore by delegate,
        Decorator<SnapshotStore>

    private class CloseCountingEventStore : EventStore by NoOpEventStore {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
        }
    }

    private object NoOpEventStore : EventStore {
        override fun append(eventStream: DomainEventStream): Mono<Void> = Mono.empty()

        override fun load(
            aggregateId: AggregateId,
            headVersion: Int,
            tailVersion: Int,
        ): Flux<DomainEventStream> = Flux.empty()

        override fun load(
            aggregateId: AggregateId,
            headEventTime: Long,
            tailEventTime: Long,
        ): Flux<DomainEventStream> = Flux.empty()

        override fun last(aggregateId: AggregateId): Mono<DomainEventStream> = Mono.empty()
    }

    private data class SimpleDomainEventStreamStub(
        override val aggregateId: AggregateId,
    ) : DomainEventStream {
        override val id: String = "stream-1"
        override val requestId: String = "request-1"
        override val header = me.ahoo.wow.messaging.DefaultHeader.empty()
        override val body = emptyList<me.ahoo.wow.api.event.DomainEvent<*>>()
        override val contextName: String = aggregateId.contextName
        override val aggregateName: String = aggregateId.aggregateName
        override val ownerId: String = "owner-1"
        override val spaceId: String = "space-1"
        override val commandId: String = "command-1"
        override val version: Int = 1
        override val size: Int = 0
        override val createTime: Long = 1000

        override fun copy(): DomainEventStream = this

        override fun iterator(): Iterator<me.ahoo.wow.api.event.DomainEvent<*>> = body.iterator()
    }
}
