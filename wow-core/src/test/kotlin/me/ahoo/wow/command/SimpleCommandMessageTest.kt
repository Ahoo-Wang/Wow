package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class SimpleCommandMessageTest {
    @Test
    fun toCommandMessage() {
        val ownerId = generateGlobalId()
        val command =
            MockCreateCommand(GlobalIdGenerator.generateAsString()).toCommandMessage(ownerId = ownerId)
        command.isReadOnly.assert().isEqualTo(false)
        command.withHeader(DefaultHeader.empty())
        command.withReadOnly()
        command.isReadOnly.assert().isEqualTo(true)
        command.ownerId.assert().isEqualTo(ownerId)
    }
}
