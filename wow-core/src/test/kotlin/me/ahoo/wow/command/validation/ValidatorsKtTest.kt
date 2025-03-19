package me.ahoo.wow.command.validation

import jakarta.validation.constraints.Positive
import me.ahoo.wow.command.CommandValidationException
import me.ahoo.wow.test.validation.TestValidator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class ValidatorsKtTest {

    @Test
    fun validateCommand() {
        TestValidator.validateCommand(MockCommandBody(qty = 1))
    }

    @Test
    fun validateCommandError() {
        Assertions.assertThrows(CommandValidationException::class.java) {
            TestValidator.validateCommand(MockCommandBody(qty = -1))
        }
    }

    data class MockCommandBody(
        @field:Positive
        val qty: Int
    )
}
