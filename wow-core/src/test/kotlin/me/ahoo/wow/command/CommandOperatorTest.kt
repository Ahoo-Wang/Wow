package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.command.CommandOperator.requiredOperator
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.messaging.DefaultHeader
import org.junit.jupiter.api.Test

class CommandOperatorTest {

    @Test
    fun getOperator() {
        val header = DefaultHeader.empty()
        header.operator.assert().isNull()
    }

    @Test
    fun getRequiredOperator() {
        assertThrownBy<IllegalStateException> {
            DefaultHeader.empty().requiredOperator
        }
    }

    @Test
    fun withOperator() {
        val header = DefaultHeader.empty().withOperator("test")
        header.operator.assert().isEqualTo("test")
    }

    @Test
    fun testWithOperator() {
        val header = DefaultHeader.empty().withOperator("test")
        header.operator.assert().isEqualTo("test")
    }
}
