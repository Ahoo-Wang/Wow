package me.ahoo.wow.models.common

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ReasonCapableTest {

    @Test
    fun `test reason property`() {
        val reasonCapable = object : ReasonCapable {
            override val reason: String = "This is a test reason."
        }
        reasonCapable.reason.assert().isEqualTo("This is a test reason.")
    }
}
