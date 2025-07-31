package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test

class CommandResultTest {
    @Test
    fun throwableAsResult() {
        val command = MockCreateCommand(generateGlobalId()).toCommandMessage()
        val actual = IllegalStateException("test").toResult(
            command,
            command.commandGatewayFunction(),
        )
        actual.id.assert().isNotBlank()
        actual.stage.assert().isEqualTo(CommandStage.SENT)
        actual.aggregateId.assert().isEqualTo(command.aggregateId.id)
        actual.tenantId.assert().isEqualTo(command.aggregateId.tenantId)
        actual.requestId.assert().isEqualTo(command.requestId)
        actual.commandId.assert().isEqualTo(command.commandId)
        actual.errorCode.assert().isEqualTo(ErrorCodes.ILLEGAL_STATE)
        actual.errorMsg.assert().isEqualTo("test")
        actual.bindingErrors.assert().isEmpty()
        actual.result.assert().isEmpty()
        actual.signalTime.assert().isGreaterThan(0)
    }
}
