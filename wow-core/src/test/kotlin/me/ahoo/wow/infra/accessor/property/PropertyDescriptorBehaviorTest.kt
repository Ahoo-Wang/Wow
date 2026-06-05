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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.toPropertyGetter
import me.ahoo.wow.infra.accessor.property.PropertyDescriptor.toPropertySetter
import org.junit.jupiter.api.Test

class PropertyDescriptorBehaviorTest {

    @Test
    fun `should create static property getter from value`() {
        val getter = "static-value".toPropertyGetter<PropertyDescriptorFixture, String>()

        getter.assert().isInstanceOf(StaticPropertyGetter::class.java)
        getter[PropertyDescriptorFixture("ignored")].assert().isEqualTo("static-value")
    }

    @Test
    fun `should create property getter from Kotlin property`() {
        val fixture = PropertyDescriptorFixture("current")
        val getter = PropertyDescriptorFixture::value.toPropertyGetter()

        getter.assert().isInstanceOf(SimplePropertyGetter::class.java)
        getter[fixture].assert().isEqualTo("current")
    }

    @Test
    fun `should create property setter from mutable Kotlin property`() {
        val fixture = PropertyDescriptorFixture("before")
        val setter = PropertyDescriptorFixture::value.toPropertySetter()

        setter.assert().isInstanceOf(SimplePropertySetter::class.java)
        setter[fixture] = "after"

        fixture.value.assert().isEqualTo("after")
    }
}

private data class PropertyDescriptorFixture(var value: String)
