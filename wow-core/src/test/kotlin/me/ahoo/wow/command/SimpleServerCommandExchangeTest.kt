package me.ahoo.wow.command

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.command.CommandAggregate
import me.ahoo.wow.modeling.command.setCommandAggregate
import org.junit.jupiter.api.Test

class SimpleServerCommandExchangeTest {

    @Test
    fun main() {
        val command = MockCreateCommand(generateGlobalId()).toCommandMessage()
        val commandExchange = SimpleServerCommandExchange(command)
        commandExchange.setAggregateProcessor(mockk()).getAggregateProcessor().assert().isNotNull()
        commandExchange.setEventStream(mockk()).getEventStream().assert().isNotNull()
        commandExchange.getAggregateVersion().assert().isNull()
        commandExchange.setAggregateVersion(1).getAggregateVersion().assert().isEqualTo(1)
    }

    @Test
    fun extractDeclaredCommand() {
        val command = MockCreateCommand(generateGlobalId()).toCommandMessage()
        val commandExchange = SimpleServerCommandExchange(command)
        commandExchange.extractDeclared(CommandMessage::class.java).assert().isNotNull()
    }

    @Test
    fun extractDeclaredCommandAggregate() {
        val command = MockCreateCommand(generateGlobalId()).toCommandMessage()
        val commandExchange = SimpleServerCommandExchange(command)
        commandExchange.setCommandAggregate(mockk())
        commandExchange.extractDeclared(CommandAggregate::class.java).assert().isNotNull()
    }

    @Test
    fun extractDeclaredEventStream() {
        val command = MockCreateCommand(generateGlobalId()).toCommandMessage()
        val commandExchange = SimpleServerCommandExchange(command)
            .setEventStream(mockk())
        commandExchange.extractDeclared(DomainEventStream::class.java).assert().isNotNull()
    }

    @Test
    fun extractDeclaredError() {
        val command = MockCreateCommand(generateGlobalId()).toCommandMessage()

        val commandExchange = SimpleServerCommandExchange(command)
        commandExchange.setError(IllegalArgumentException())

        commandExchange.extractDeclared(RuntimeException::class.java).assert().isNotNull()
    }
}
