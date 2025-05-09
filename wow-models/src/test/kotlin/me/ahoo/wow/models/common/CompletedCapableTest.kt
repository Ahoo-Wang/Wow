package me.ahoo.wow.models.common

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class CompletedCapableTest {

    @Test
    fun `test completed property`() {
        val completedCapable = object : CompletedCapable {
            override val completed: Boolean = true
        }
        completedCapable.completed.assert().isTrue()
    }
}
