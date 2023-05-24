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

import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.asPropertyGetter
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.asPropertySetter
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
        assertThat(PropertyDescriptor.asGetterName("name"), `is`("getName"))
    }

    @Test
    fun asSetterName() {
        assertThat(PropertyDescriptor.asSetterName("name"), `is`("setName"))
    }

    @Test
    fun asPropertyGetter() {
        val idField = MockProperty::class.java.getDeclaredField("id")
        val propertyGetter = idField.asPropertyGetter<MockProperty, String>()
        assertThat(propertyGetter, instanceOf(MethodPropertyGetter::class.java))
        assertThat(propertyGetter.get(MockProperty("1")), equalTo("1"))
    }

    @Test
    fun asPropertyGetterWhenMethodNotFound() {
        val idField = MockPropertyWithoutMethod::class.java.getDeclaredField("id")
        val propertyGetter = idField.asPropertyGetter<MockPropertyWithoutMethod, String>()
        assertThat(propertyGetter, instanceOf(FieldPropertyGetter::class.java))
        assertThat(propertyGetter.get(MockPropertyWithoutMethod("1")), equalTo("1"))
    }

    @Test
    fun asPropertySetterWhenFinal() {
        val idField = MockProperty::class.java.getDeclaredField("id")
        val propertySetter = idField.asPropertySetter<MockPropertyWithoutMethod, String>()
        assertThat(propertySetter, nullValue())
    }
}

data class MockProperty(val id: String)

@Suppress("UnusedPrivateMember")
class MockPropertyWithoutMethod(private val id: String)
