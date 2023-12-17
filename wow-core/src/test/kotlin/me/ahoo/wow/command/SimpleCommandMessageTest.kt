package me.ahoo.wow.command

import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class SimpleCommandMessageTest {
    @Test
    fun asCommandMessage() {
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString()).toCommandMessage()
        assertThat(command.isReadOnly, equalTo(false))
        command.withHeader(DefaultHeader.empty())
        command.withReadOnly()
        assertThat(command.isReadOnly, equalTo(true))
    }
}
