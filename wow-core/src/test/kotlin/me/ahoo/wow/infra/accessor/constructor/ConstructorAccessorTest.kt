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

package me.ahoo.wow.infra.accessor.constructor

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConstructorAccessorTest {

    @Test
    fun `invoke with array should support private one-arg constructor`() {
        val constructor = OneArgConstructorAccessorFixture::class.java.getDeclaredConstructor(String::class.java)
        val accessor = DefaultConstructorAccessor(constructor)

        constructor.canAccess(null).assert().isTrue()
        accessor.constructor.assert().isSameAs(constructor)
        accessor.invoke(arrayOf("created")).value.assert().isEqualTo("created")
    }

    @Test
    fun `newInstance0 should support private no-arg constructor`() {
        val constructor = NoArgConstructorAccessorFixture::class.java.getDeclaredConstructor()
        val accessor = DefaultConstructorAccessor(constructor)

        accessor.newInstance0().value.assert().isEqualTo("created")
    }

    @Test
    fun `newInstance1 should support private one-arg constructor`() {
        val constructor = OneArgConstructorAccessorFixture::class.java.getDeclaredConstructor(String::class.java)
        val accessor = DefaultConstructorAccessor(constructor)

        accessor.newInstance1("created").value.assert().isEqualTo("created")
    }

    @Test
    fun `newInstance2 should support private two-arg constructor`() {
        val constructor = TwoArgConstructorAccessorFixture::class.java.getDeclaredConstructor(
            String::class.java,
            String::class.java
        )
        val accessor = DefaultConstructorAccessor(constructor)

        accessor.newInstance2("created", "tenant").value.assert().isEqualTo("created:tenant")
    }

    @Test
    fun `newInstance1 should propagate original constructor exception`() {
        val constructor = FailingConstructorAccessorFixture::class.java.getDeclaredConstructor(String::class.java)
        val accessor = DefaultConstructorAccessor(constructor)

        val error = assertThrows<IllegalStateException> {
            accessor.newInstance1("bad")
        }
        error.message.assert().isEqualTo("boom bad")
    }
}

private class NoArgConstructorAccessorFixture private constructor() {
    val value: String = "created"
}

private class OneArgConstructorAccessorFixture private constructor(val value: String)

private class TwoArgConstructorAccessorFixture private constructor(value: String, tenantId: String) {
    val value: String = "$value:$tenantId"
}

private class FailingConstructorAccessorFixture private constructor(value: String) {
    init {
        error("boom $value")
    }
}
