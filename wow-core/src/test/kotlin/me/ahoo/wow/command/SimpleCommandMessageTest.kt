package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class SimpleCommandMessageTest {
    @Test
    fun `should to command message`() {
        val ownerId = generateGlobalId()
        val command =
            MockCreateCommand(GlobalIdGenerator.generateAsString()).toCommandMessage(ownerId = ownerId)
        command.isReadOnly.assert().isEqualTo(false)
        command.withHeader(DefaultHeader.empty())
        command.withReadOnly()
        command.isReadOnly.assert().isEqualTo(true)
        command.ownerId.assert().isEqualTo(ownerId)
    }

    @Test
    fun `should create command message with supplied header`() {
        val header = DefaultHeader.empty().with("source", "original")

        val command = MockCreateCommand(GlobalIdGenerator.generateAsString()).toCommandMessage(header = header)
        header["source"] = "mutated"

        command.header.assert().isSameAs(header)
        command.header["source"].assert().isEqualTo("mutated")
        command.withReadOnly()
        header.isReadOnly.assert().isTrue()
    }

    @Test
    fun `should copy command message with independent header`() {
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString()).toCommandMessage(
            header = DefaultHeader.empty().with("source", "original")
        )
        val copied = command.copy()
        copied.header["source"] = "mutated"
        command.withReadOnly()

        command.header["source"].assert().isEqualTo("original")
        copied.header["source"].assert().isEqualTo("mutated")
    }
}
