package me.ahoo.wow.exception

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
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
        assertThat(exception.errorCode, equalTo("errorCode"))
    }
}
