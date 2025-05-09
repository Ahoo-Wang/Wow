package me.ahoo.wow.models.common

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class QuantityCapableTest {

    @Test
    fun `test qty property`() {
        val quantityCapable = object : QuantityCapable {
            override val qty: Int = 10
        }
        quantityCapable.qty.assert().isEqualTo(10)
    }
}
