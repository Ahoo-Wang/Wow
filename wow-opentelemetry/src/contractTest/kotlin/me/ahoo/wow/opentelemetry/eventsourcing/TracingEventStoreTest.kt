package me.ahoo.wow.opentelemetry.eventsourcing

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.opentelemetry.Tracing.tracing
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec

class TracingEventStoreTest : EventStoreSpec() {
    override fun createEventStore(): EventStore {
        return InMemoryEventStore().tracing()
    }

    override fun loadEventStreamGivenWrongVersion() = Unit
}
