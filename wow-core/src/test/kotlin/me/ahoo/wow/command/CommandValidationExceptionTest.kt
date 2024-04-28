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
        val commandMessage = MockCreateCommand(GlobalIdGenerator.generateAsString()).toCommandMessage()
        val path = mockk<Path>()
        every { path.toString() } returns "name"
        val constraintViolation = mockk<ConstraintViolation<MockCreateCommand>> {
            every { propertyPath } returns path
            every { message } returns "name is blank"
        }
        val exception = CommandValidationException(commandMessage, setOf(constraintViolation))
        assertThat(exception.errorCode, equalTo(COMMAND_VALIDATION))
        assertThat(exception.message, equalTo("name:name is blank"))
        assertThat(exception.errorMsg, equalTo("name:name is blank"))
        assertThat(
            exception.bindingErrors.first().name,
            equalTo(constraintViolation.propertyPath.toString())
        )
        assertThat(
            exception.bindingErrors.first().msg,
            equalTo(constraintViolation.message)
        )
    }

    @Test
    fun testIfEmpty() {
        val commandMessage = MockCreateCommand(GlobalIdGenerator.generateAsString()).toCommandMessage()
        val exception = CommandValidationException(commandMessage, setOf())
        assertThat(exception.errorCode, equalTo(COMMAND_VALIDATION))
        assertThat(exception.message, equalTo("Command validation failed."))
        assertThat(exception.errorMsg, equalTo("Command validation failed."))
    }
}
