package me.ahoo.wow.infra.accessor.property

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.toPropertyGetter
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.toPropertySetter
import org.junit.jupiter.api.Test

class PropertyDescriptorTest {

    var data: String = "value"

    @Test
    fun `should create static property getter`() {
        val propertyGetter = "value".toPropertyGetter<Any?, String>()
        propertyGetter.assert().isInstanceOf(StaticPropertyGetter::class.java)
        propertyGetter[null].assert().isEqualTo("value")
    }

    @Test
    fun `should create property getter`() {
        val propertyGetter = PropertyDescriptorTest::data.toPropertyGetter()
        propertyGetter.assert().isInstanceOf(SimplePropertyGetter::class.java)
        val instance = PropertyDescriptorTest()
        propertyGetter[instance].assert().isEqualTo("value")
    }

    @Test
    fun `should create property setter`() {
        val propertySetter = PropertyDescriptorTest::data.toPropertySetter()
        propertySetter.assert().isInstanceOf(SimplePropertySetter::class.java)
        val instance = PropertyDescriptorTest()
        val newValue = "newValue"
        propertySetter[instance] = newValue
        instance.data.assert().isEqualTo(newValue)
    }
}
