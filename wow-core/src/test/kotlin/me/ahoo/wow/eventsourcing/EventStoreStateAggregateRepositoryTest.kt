package me.ahoo.wow.eventsourcing

import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.tck.eventsourcing.StateAggregateRepositorySpec

class EventStoreStateAggregateRepositoryTest : StateAggregateRepositorySpec() {
    override fun createStateAggregateRepository(
        aggregateFactory: StateAggregateFactory,
        eventStore: EventStore
    ): StateAggregateRepository {
        return EventStoreStateAggregateRepository(aggregateFactory, eventStore)
    }
}
