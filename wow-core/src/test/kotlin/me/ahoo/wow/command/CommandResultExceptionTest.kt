package me.ahoo.wow.command

import io.mockk.every
import io.mockk.mockk
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CommandResultExceptionTest {

    @Test
    fun ctor() {
        val commandResult = mockk<CommandResult> {
            every { errorCode } returns "errorCode"
            every { errorMsg } returns "errorMsg"
        }
        val commandResultException = CommandResultException(commandResult)
        assertThat(commandResultException.errorCode, equalTo(commandResult.errorCode))
        assertThat(commandResultException.errorMsg, equalTo(commandResult.errorMsg))
    }
}
