package me.ahoo.wow.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class CommandResultExceptionTest {

    @Test
    fun ctor() {
        val commandResult = mockk<CommandResult> {
            every { errorCode } returns "errorCode"
            every { errorMsg } returns "errorMsg"
            every { bindingErrors } returns emptyList()
        }
        val commandResultException = CommandResultException(commandResult)
        commandResultException.errorCode.assert().isEqualTo(commandResult.errorCode)
        commandResultException.errorMsg.assert().isEqualTo(commandResult.errorMsg)
        commandResultException.bindingErrors.assert().isEqualTo(commandResult.bindingErrors)
    }
}
