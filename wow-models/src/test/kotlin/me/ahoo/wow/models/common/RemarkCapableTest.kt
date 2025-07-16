package me.ahoo.wow.models.common

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class RemarkCapableTest {

    @Test
    fun `test remark property`() {
        val remarkCapable = object : RemarkCapable {
            override val remark: String = "This is a test remark."
        }
        remarkCapable.remark.assert().isEqualTo("This is a test remark.")
    }

    @Test
    fun `test NullableRemarkCapable`() {
        val nullableCapable = object : NullableRemarkCapable {
            override val remark: String? = null
        }
        nullableCapable.remark.assert().isNull()
    }
}
