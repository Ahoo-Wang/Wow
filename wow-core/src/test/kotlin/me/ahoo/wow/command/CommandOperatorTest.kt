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
    fun `should get operator`() {
        val header = DefaultHeader.empty()
        header.operator.assert().isNull()
    }

    @Test
    fun `should get required operator`() {
        assertThrownBy<IllegalStateException> {
            DefaultHeader.empty().requiredOperator
        }
    }

    @Test
    fun `should with operator`() {
        val header = DefaultHeader.empty().withOperator("test")
        header.operator.assert().isEqualTo("test")
    }

    @Test
    fun `should test with operator`() {
        val header = DefaultHeader.empty().withOperator("test")
        header.operator.assert().isEqualTo("test")
    }
}
