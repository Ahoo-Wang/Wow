package me.ahoo.wow.command

import io.mockk.mockk
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.id.GlobalIdGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class SimpleServerCommandExchangeTest {

    @Test
    fun set() {
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString()).asCommandMessage()
        val commandExchange = SimpleServerCommandExchange(command)
        assertThat(commandExchange.setAggregateProcessor(mockk()).getAggregateProcessor(), notNullValue())
        assertThat(commandExchange.setEventStream(mockk()).getEventStream(), notNullValue())
    }

    @Test
    fun extractDeclaredEventStream() {
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString()).asCommandMessage()
        val commandExchange = SimpleServerCommandExchange(command)
            .setEventStream(mockk())
        assertThat(commandExchange.extractDeclared(DomainEventStream::class.java), notNullValue())
    }

    @Test
    fun extractDeclaredError() {
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString()).asCommandMessage()
        val commandExchange = SimpleServerCommandExchange(command)
            .setError(IllegalArgumentException())
        assertThat(commandExchange.extractDeclared(RuntimeException::class.java), notNullValue())
    }
}