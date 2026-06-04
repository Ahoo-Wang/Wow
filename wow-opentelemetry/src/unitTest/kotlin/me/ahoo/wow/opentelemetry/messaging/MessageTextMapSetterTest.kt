package me.ahoo.wow.opentelemetry.messaging

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

class MessageTextMapSetterTest {
    private val textMapSetter = MessageTextMapSetter<CommandMessage<*>>()
    private val key = "key"
    private val value = "value"

    @Test
    fun `should handle null carrier`() {
        textMapSetter.set(null, key, value)
    }

    @Test
    fun `should set value on message header`() {
        val command = MockCreateAggregate(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
        ).toCommandMessage()

        MessageTextMapSetter<CommandMessage<*>>().set(command, key, value)
        command.header[key].assert().isEqualTo(value)
    }

    @Test
    fun `should not set value when message is read only`() {
        val command = MockCreateAggregate(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
        ).toCommandMessage().withReadOnly()

        MessageTextMapSetter<CommandMessage<*>>().set(command, key, value)
        command.header[key].assert().isNull()
    }
}
