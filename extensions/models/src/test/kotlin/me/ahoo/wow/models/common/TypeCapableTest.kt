package me.ahoo.wow.models.common

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class TypeCapableTest {

    @Test
    fun `test type property`() {
        val typeCapable = object : TypeCapable {
            override val type: String = TypeCapable.TYPE
        }
        typeCapable.type.assert().isEqualTo(TypeCapable.TYPE)
    }

    @Test
    fun `test NotBlankTypeCapable`() {
        val notBlankTypeCapable = object : NotBlankTypeCapable {
            override val type: String = "testType"
        }
        notBlankTypeCapable.type.assert().isEqualTo("testType")
    }

    @Test
    fun `test PolymorphicTypeCapable`() {
        val polymorphicTypeCapable = object : PolymorphicTypeCapable {
            override val type: String = "testType"
        }
        polymorphicTypeCapable.type.assert().isEqualTo("testType")
    }
}
