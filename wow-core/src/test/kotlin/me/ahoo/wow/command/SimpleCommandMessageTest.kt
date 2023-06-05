package me.ahoo.wow.command

import me.ahoo.wow.id.GlobalIdGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class SimpleCommandMessageTest {
    @Test
    fun asCommandMessage() {
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString()).asCommandMessage()
        assertThat(command.isReadOnly, equalTo(false))
        command.withReadOnly()
        assertThat(command.isReadOnly, equalTo(true))
    }
}
