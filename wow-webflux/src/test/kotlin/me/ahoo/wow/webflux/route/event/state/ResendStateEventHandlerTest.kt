package me.ahoo.wow.webflux.route.event.state

import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class ResendStateEventHandlerTest {

    @Test
    fun handle() {
        val eventStore = InMemoryEventStore()
        val commandMessage = MockCreateAggregate("1", "data").asCommandMessage()
        val eventStream = MockAggregateCreated(GlobalIdGenerator.generateAsString())
            .asDomainEventStream(commandMessage, 0)
        eventStore.appendStream(eventStream).test().verifyComplete()
        val handlerFunction = ResendStateEventHandler(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            eventStore = eventStore,
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
