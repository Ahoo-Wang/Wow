package me.ahoo.wow.command.validation

import me.ahoo.wow.command.MockCreateCommand
import me.ahoo.wow.id.GlobalIdGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class NoOpValidatorTest {

    @Test
    fun validate() {
        val actual = NoOpValidator.validate(MockCreateCommand(GlobalIdGenerator.generateAsString()))
        assertThat(actual, empty())
    }

    @Test
    fun validateProperty() {
        val actual = NoOpValidator.validateProperty(
            MockCreateCommand(
                GlobalIdGenerator.generateAsString()
            ),
            ""
        )
        assertThat(actual, empty())
    }

    @Test
    fun validateValue() {
        val actual = NoOpValidator.validateValue(
            MockCreateCommand::class.java,
            "",
            MockCreateCommand(GlobalIdGenerator.generateAsString())
        )
        assertThat(actual, empty())
    }

    @Test
    fun getConstraintsForClass() {
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            NoOpValidator.getConstraintsForClass(MockCreateCommand::class.java)
        }
    }

    @Test
    fun unwrap() {
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            NoOpValidator.unwrap(MockCreateCommand::class.java)
        }
    }

    @Test
    fun forExecutables() {
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            NoOpValidator.forExecutables()
        }
    }
}
