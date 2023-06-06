package me.ahoo.wow.eventsourcing.state

import me.ahoo.wow.tck.eventsourcing.state.StateEventBusSpec

class InMemoryStateEventBusTest : StateEventBusSpec() {
    override fun createMessageBus(): StateEventBus {
        return InMemoryStateEventBus()
    }
}
