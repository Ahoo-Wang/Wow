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

package me.ahoo.wow.infra.accessor.function

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.full.declaredFunctions

class SimpleFunctionAccessorTest {

    @Test
    fun `should expose function metadata and invoke accessible function`() {
        val function = FunctionAccessorFixture::class.declaredFunctions.first { it.name == "greet" }
        val accessor = SimpleFunctionAccessor<FunctionAccessorFixture, String>(function)
        val fixture = FunctionAccessorFixture("hello")

        accessor.name.assert().isEqualTo("greet")
        accessor.targetType.assert().isEqualTo(FunctionAccessorFixture::class.java)
        accessor.invoke(fixture, arrayOf("wow")).assert().isEqualTo("hello wow")
    }

    @Test
    fun `should invoke accessible function with single argument`() {
        val function = FunctionAccessorFixture::class.declaredFunctions.first { it.name == "greet" }
        val accessor = SimpleFunctionAccessor<FunctionAccessorFixture, String>(function)
        val fixture = FunctionAccessorFixture("hello")

        accessor.invokeSingle(fixture, "wow").assert().isEqualTo("hello wow")
    }

    @Test
    fun `should propagate original exception when single invoke fails`() {
        val function = FunctionAccessorFixture::class.declaredFunctions.first { it.name == "boom" }
        val accessor = SimpleFunctionAccessor<FunctionAccessorFixture, String>(function)
        val fixture = FunctionAccessorFixture("hello")

        val error = assertThrows<IllegalStateException> {
            accessor.invokeSingle(fixture, "wow")
        }
        error.message.assert().isEqualTo("boom wow")
    }
}

private class FunctionAccessorFixture(private val prefix: String) {
    private fun greet(name: String): String = "$prefix $name"

    private fun boom(name: String): String {
        error("boom $name")
    }
}
