package me.ahoo.wow.exception

import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class PreconditionsTest {

    @Test
    fun `should throw WowException when check fails`() {
        assertThrownBy<WowException> {
            Preconditions.check(false, "errorCode") {
                "error message"
            }
        }
    }
}
