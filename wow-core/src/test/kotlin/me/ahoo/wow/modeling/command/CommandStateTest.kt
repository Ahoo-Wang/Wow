package me.ahoo.wow.modeling.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class CommandStateTest {

    @Test
    fun onSourcing() {
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            CommandState.EXPIRED.onSourcing(
                mockk(),
                mockk {
                    every { id } returns generateGlobalId()
                }
            )
        }
    }

    @Test
    fun onStore() {
        Assertions.assertThrows(UnsupportedOperationException::class.java) {
            CommandState.EXPIRED.onStore(
                mockk(),
                mockk {
                    every { id } returns generateGlobalId()
                }
            )
        }
    }
}
