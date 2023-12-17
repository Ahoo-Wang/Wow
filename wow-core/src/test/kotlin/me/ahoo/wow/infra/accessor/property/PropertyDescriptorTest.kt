/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.infra.accessor.property

import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.asPropertySetter
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.toPropertyGetter
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class PropertyDescriptorTest {

    @Test
    fun capitalize() {
        assertThat(PropertyDescriptor.capitalize("name"), `is`("Name"))
    }

    @Test
    fun asGetterName() {
        assertThat(PropertyDescriptor.toGetterName("name"), `is`("getName"))
    }

    @Test
    fun asSetterName() {
        assertThat(PropertyDescriptor.toSetterName("name"), `is`("setName"))
    }

    @Test
    fun asPropertyGetter() {
        val idField = MockPropertyGetter::class.java.getDeclaredField("id")
        val propertyGetter = idField.toPropertyGetter<MockPropertyGetter, String>()
        assertThat(propertyGetter, instanceOf(MethodPropertyGetter::class.java))
        assertThat(propertyGetter.get(MockPropertyGetter("1")), equalTo("1"))
    }

    @Test
    fun asPropertySetter() {
        val idField = MockPropertySetter::class.java.getDeclaredField("id")
        val propertyGetter = idField.asPropertySetter<MockPropertySetter, String>()!!
        assertThat(propertyGetter, instanceOf(MethodPropertySetter::class.java))
        val mockProperty = MockPropertySetter("1")
        propertyGetter[mockProperty] = "2"
        assertThat(mockProperty.id, equalTo("2"))
    }

    @Test
    fun asFieldSetter() {
        val idField = MockPropertyWithoutMethod::class.java.getDeclaredField("id")
        val propertySetter = idField.asPropertySetter<MockPropertyWithoutMethod, String>()!!
        assertThat(propertySetter, instanceOf(FieldPropertySetter::class.java))
        val mockProperty = MockPropertyWithoutMethod("1")
        propertySetter[mockProperty] = "2"
        val propertyGetter = idField.toPropertyGetter<MockPropertyWithoutMethod, String>()
        assertThat(propertyGetter[mockProperty], equalTo("2"))
    }

    @Test
    fun asPropertyGetterWhenMethodNotFound() {
        val idField = MockPropertyWithoutMethod::class.java.getDeclaredField("id")
        val propertyGetter = idField.toPropertyGetter<MockPropertyWithoutMethod, String>()
        assertThat(propertyGetter, instanceOf(FieldPropertyGetter::class.java))
        assertThat(propertyGetter[MockPropertyWithoutMethod("1")], equalTo("1"))
    }

    @Test
    fun asPropertySetterWhenFinal() {
        val idField = MockPropertyGetter::class.java.getDeclaredField("id")
        val propertySetter = idField.asPropertySetter<MockPropertyWithoutMethod, String>()
        assertThat(propertySetter, nullValue())
    }
}

data class MockPropertyGetter(val id: String)
data class MockPropertySetter(var id: String)

@Suppress("UnusedPrivateMember")
class MockPropertyWithoutMethod(private var id: String)
