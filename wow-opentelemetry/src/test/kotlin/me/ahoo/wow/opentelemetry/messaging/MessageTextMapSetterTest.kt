package me.ahoo.wow.opentelemetry.messaging

import me.ahoo.wow.command.CommandMessage
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class MessageTextMapSetterTest {
    private val textMapSetter = MessageTextMapSetter<CommandMessage<*>>()
    private val key = "key"
    private val value = "value"

    @Test
    fun setIfNull() {
        textMapSetter.set(null, key, value)
    }

    @Test
    fun set() {
        val command = MockCreateAggregate(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
        ).asCommandMessage()

        MessageTextMapSetter<CommandMessage<*>>().set(command, key, value)
        assertThat(command.header[key], equalTo(value))
    }

    @Test
    fun setIfReadyOnly() {
        val command = MockCreateAggregate(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
        ).asCommandMessage().withReadOnly()

        MessageTextMapSetter<CommandMessage<*>>().set(command, key, value)
        assertThat(command.header[key], nullValue())
    }
}
