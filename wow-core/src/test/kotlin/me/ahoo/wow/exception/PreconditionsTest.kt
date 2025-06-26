package me.ahoo.wow.exception

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class PreconditionsTest {

    @Test
    fun check() {
        val exception = Assertions.assertThrows(WowException::class.java) {
            Preconditions.check(false, "errorCode") {
                "error message"
            }
        }
        exception.errorCode.assert().isEqualTo("errorCode")
    }
}
