package me.ahoo.wow.command.validation

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.command.MockCreateCommand
import me.ahoo.wow.id.GlobalIdGenerator
import org.junit.jupiter.api.Test

class NoOpValidatorTest {

    @Test
    fun `should validate`() {
        val actual = NoOpValidator.validate(MockCreateCommand(GlobalIdGenerator.generateAsString()))
        actual.assert().isEmpty()
    }

    @Test
    fun `should validate property`() {
        val actual = NoOpValidator.validateProperty(
            MockCreateCommand(
                GlobalIdGenerator.generateAsString(),
            ),
            "",
        )
        actual.assert().isEmpty()
    }

    @Test
    fun `should validate value`() {
        val actual = NoOpValidator.validateValue(
            MockCreateCommand::class.java,
            "",
            MockCreateCommand(GlobalIdGenerator.generateAsString()),
        )
        actual.assert().isEmpty()
    }

    @Test
    fun `should get constraints for class`() {
        assertThrownBy<UnsupportedOperationException> {
            NoOpValidator.getConstraintsForClass(MockCreateCommand::class.java)
        }
    }

    @Test
    fun `should unwrap`() {
        assertThrownBy<UnsupportedOperationException> {
            NoOpValidator.unwrap(MockCreateCommand::class.java)
        }
    }

    @Test
    fun `should for executables`() {
        assertThrownBy<UnsupportedOperationException> {
            NoOpValidator.forExecutables()
        }
    }
}
