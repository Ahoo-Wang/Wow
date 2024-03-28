package me.ahoo.wow.infra.accessor.property

import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.toPropertyGetter
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.toPropertySetter
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class PropertyDescriptorTest {

    var data: String = "value"

    @Test
    fun toStaticPropertyGetter() {
        val propertyGetter = "value".toPropertyGetter<Any?, String>()
        assertThat(propertyGetter, instanceOf(StaticPropertyGetter::class.java))
        assertThat(propertyGetter[null], equalTo("value"))
    }

    @Test
    fun toPropertyGetter() {
        val propertyGetter = PropertyDescriptorTest::data.toPropertyGetter()
        assertThat(propertyGetter, instanceOf(SimplePropertyGetter::class.java))
        val instance = PropertyDescriptorTest()
        assertThat(propertyGetter[instance], equalTo("value"))
    }

    @Test
    fun toPropertySetter() {
        val propertySetter = PropertyDescriptorTest::data.toPropertySetter()
        assertThat(propertySetter, instanceOf(SimplePropertySetter::class.java))
        val instance = PropertyDescriptorTest()
        val newValue = "newValue"
        propertySetter[instance] = newValue
        assertThat(instance.data, equalTo(newValue))
    }
}
