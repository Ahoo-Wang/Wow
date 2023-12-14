package me.ahoo.compensation.domain

import me.ahoo.wow.id.GlobalIdGenerator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class ExecutionFailedStateTest {
    @Test
    fun ctor() {
        val state = ExecutionFailedState(GlobalIdGenerator.generateAsString())
        Assertions.assertThrows(UninitializedPropertyAccessException::class.java) {
            state.eventId
        }
        Assertions.assertThrows(UninitializedPropertyAccessException::class.java) {
            state.processor
        }
        Assertions.assertThrows(UninitializedPropertyAccessException::class.java) {
            state.functionKind
        }
        Assertions.assertThrows(UninitializedPropertyAccessException::class.java) {
            state.error
        }
    }
}
