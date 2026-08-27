package me.ahoo.wow.opentelemetry.messaging

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.opentelemetry.Tracing.tracing
import me.ahoo.wow.tck.command.CommandBusSpec

class TracingLocalCommandBusTest : CommandBusSpec() {
    override fun createMessageBus(): CommandBus {
        return InMemoryCommandBus().tracing()
    }
}
