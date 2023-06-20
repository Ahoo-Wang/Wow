package me.ahoo.wow.command

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.Path
import me.ahoo.wow.exception.ErrorCodes.COMMAND_VALIDATION
import me.ahoo.wow.id.GlobalIdGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CommandValidationExceptionTest {
    @Test
    fun test() {
        val commandMessage = MockCreateCommand(GlobalIdGenerator.generateAsString()).asCommandMessage()
        val path = mockk<Path>()
        every { path.toString() } returns "name"
        val constraintViolation = mockk<ConstraintViolation<MockCreateCommand>> {
            every { propertyPath } returns path
            every { message } returns "name is blank"
        }
        val exception = CommandValidationException(commandMessage, setOf(constraintViolation))
        assertThat(exception.errorCode, equalTo(COMMAND_VALIDATION))
    }
}
