package me.ahoo.wow.opentelemetry.messaging

import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.opentelemetry.Tracing.tracing
import me.ahoo.wow.tck.eventsourcing.state.StateEventBusSpec

class TracingLocalStateEventBusTest : StateEventBusSpec() {
    override fun createMessageBus(): StateEventBus {
        return InMemoryStateEventBus().tracing()
    }
}
