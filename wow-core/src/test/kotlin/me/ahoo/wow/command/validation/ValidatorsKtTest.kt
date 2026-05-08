package me.ahoo.wow.command.validation

import jakarta.validation.constraints.Positive
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.command.CommandValidationException
import me.ahoo.wow.test.validation.TestValidator
import org.junit.jupiter.api.Test

class ValidatorsKtTest {

    @Test
    fun `should validate valid command`() {
        TestValidator.validateCommand(MockCommandBody(qty = 1))
    }

    @Test
    fun `should throw CommandValidationException when validation fails`() {
        assertThrownBy<CommandValidationException> {
            TestValidator.validateCommand(MockCommandBody(qty = -1))
        }
    }

    data class MockCommandBody(
        @field:Positive
        val qty: Int
    )
}
