package me.ahoo.wow.command

import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class SimpleCommandMessageTest {
    @Test
    fun toCommandMessage() {
        val ownerId = generateGlobalId()
        val command =
            MockCreateCommand(GlobalIdGenerator.generateAsString()).toCommandMessage(ownerId = ownerId)
        assertThat(command.isReadOnly, equalTo(false))
        command.withHeader(DefaultHeader.empty())
        command.withReadOnly()
        assertThat(command.isReadOnly, equalTo(true))
        assertThat(command.ownerId, equalTo(ownerId))
    }
}
