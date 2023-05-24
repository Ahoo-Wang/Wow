package me.ahoo.wow.command

import me.ahoo.wow.tck.command.CommandBusSpec

class LocalFirstCommandBusTest : CommandBusSpec() {
    override fun createCommandBus(): CommandBus {
        return LocalFirstCommandBus(distributedCommandBus = InMemoryCommandBus())
    }
}
