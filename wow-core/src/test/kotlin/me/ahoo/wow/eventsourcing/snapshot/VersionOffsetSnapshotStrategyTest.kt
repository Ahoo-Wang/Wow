package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.state.SimpleStateEventExchange
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotStrategySpec
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class VersionOffsetSnapshotStrategyTest : SnapshotStrategySpec() {
    override fun createSnapshotStrategy(): SnapshotStrategy {
        return VersionOffsetSnapshotStrategy(
            snapshotRepository = InMemorySnapshotRepository(),
        )
    }

    @Test
    fun onEventWhenSave() {
        val aggregateId = aggregateMetadata.aggregateId()
        val createdEventStream =
            MockAggregateCreated(GlobalIdGenerator.generateAsString())
                .toDomainEventStream(GivenInitializationCommand(aggregateId), DEFAULT_VERSION_OFFSET)
        val state = MockStateAggregate(createdEventStream.aggregateId.id)
        val stateEvent = createdEventStream.toStateEvent(state)
        val exchange = SimpleStateEventExchange(stateEvent)
        snapshotStrategy.onEvent(exchange)
            .test()
            .verifyComplete()
    }
}
