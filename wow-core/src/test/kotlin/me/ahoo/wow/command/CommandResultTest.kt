package me.ahoo.wow.command

import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.id.GlobalIdGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CommandResultTest {
    @Test
    fun throwableAsResult() {
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString()).toCommandMessage()
        val actual = IllegalStateException("test").toResult(command, processorName = "test")
        assertThat(actual.stage, equalTo(CommandStage.SENT))
        assertThat(actual.aggregateId, equalTo(command.aggregateId.id))
        assertThat(actual.tenantId, equalTo(command.aggregateId.tenantId))
        assertThat(actual.requestId, equalTo(command.requestId))
        assertThat(actual.commandId, equalTo(command.commandId))
        assertThat(actual.errorCode, equalTo(ErrorCodes.ILLEGAL_STATE))
        assertThat(actual.errorMsg, equalTo("test"))
    }
}
