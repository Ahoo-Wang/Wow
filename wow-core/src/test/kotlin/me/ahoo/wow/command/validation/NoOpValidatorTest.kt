package me.ahoo.wow.command.validation

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.command.MockCreateCommand
import me.ahoo.wow.id.GlobalIdGenerator
import org.junit.jupiter.api.Test

class NoOpValidatorTest {

    @Test
    fun validate() {
        val actual = NoOpValidator.validate(MockCreateCommand(GlobalIdGenerator.generateAsString()))
        actual.assert().isEmpty()
    }

    @Test
    fun validateProperty() {
        val actual = NoOpValidator.validateProperty(
            MockCreateCommand(
                GlobalIdGenerator.generateAsString(),
            ),
            "",
        )
        actual.assert().isEmpty()
    }

    @Test
    fun validateValue() {
        val actual = NoOpValidator.validateValue(
            MockCreateCommand::class.java,
            "",
            MockCreateCommand(GlobalIdGenerator.generateAsString()),
        )
        actual.assert().isEmpty()
    }

    @Test
    fun getConstraintsForClass() {
        assertThrownBy<UnsupportedOperationException> {
            NoOpValidator.getConstraintsForClass(MockCreateCommand::class.java)
        }
    }

    @Test
    fun unwrap() {
        assertThrownBy<UnsupportedOperationException> {
            NoOpValidator.unwrap(MockCreateCommand::class.java)
        }
    }

    @Test
    fun forExecutables() {
        assertThrownBy<UnsupportedOperationException> {
            NoOpValidator.forExecutables()
        }
    }
}
