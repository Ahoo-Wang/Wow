package me.ahoo.wow.webflux.route.event.state

import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Clock

class ResendStateEventHandlerTest {

    @Test
    fun handle() {
        val snapshotRepository = InMemorySnapshotRepository()
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
        val stateAggregate =
            ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId).block()!!
        val snapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()

        val eventStore = InMemoryEventStore()
        val commandMessage = MockCreateAggregate("1", "data").toCommandMessage(
            aggregateId = aggregateId.id,
            tenantId = aggregateId.tenantId
        )
        val eventStream = MockAggregateCreated(generateGlobalId())
            .toDomainEventStream(commandMessage, 0)
        eventStore.appendStream(eventStream).test().verifyComplete()
        val handlerFunction = ResendStateEventHandler(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            snapshotRepository = snapshotRepository,
            stateEventCompensator = StateEventCompensator(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                eventStore = eventStore,
                stateEventBus = InMemoryStateEventBus(),
            )
        )
        handlerFunction.handle("(0)", 10)
            .test()
            .consumeNextWith {
                assertThat(it.size, equalTo(1))
            }.verifyComplete()
    }
}
