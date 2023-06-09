package me.ahoo.wow.command

import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.command.CommandOperator.requiredOperator
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.messaging.DefaultHeader
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class CommandOperatorTest {

    @Test
    fun getOperator() {
        val header = DefaultHeader.empty()
        assertThat(header.operator, nullValue())
    }

    @Test
    fun getRequiredOperator() {
        Assertions.assertThrows(IllegalStateException::class.java) {
            DefaultHeader.empty().requiredOperator
        }
    }

    @Test
    fun withOperator() {
        val header = DefaultHeader.empty().withOperator("test")
        assertThat(header.operator, equalTo("test"))
    }

    @Test
    fun testWithOperator() {
        val header = DefaultHeader.empty().withOperator("test")
        assertThat(header.operator, equalTo("test"))
    }
}
