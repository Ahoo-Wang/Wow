package me.ahoo.wow.modeling.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test

class CommandStateTest {

    @Test
    fun `should throw UnsupportedOperationException on EXPIRED onSourcing`() {
        assertThrownBy<UnsupportedOperationException> {
            CommandState.EXPIRED.onSourcing(
                mockk(),
                mockk {
                    every { id } returns generateGlobalId()
                }
            )
        }
    }

    @Test
    fun `should throw UnsupportedOperationException on EXPIRED onStore`() {
        assertThrownBy<UnsupportedOperationException> {
            CommandState.EXPIRED.onStore(
                mockk(),
                mockk {
                    every { id } returns generateGlobalId()
                }
            )
        }
    }
}
