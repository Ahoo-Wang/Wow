package me.ahoo.wow.opentelemetry.messaging

import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.opentelemetry.Tracing.tracing
import me.ahoo.wow.tck.event.DomainEventBusSpec

class TracingLocalEventBusTest : DomainEventBusSpec() {
    override fun createMessageBus(): DomainEventBus {
        return InMemoryDomainEventBus().tracing()
    }
}
