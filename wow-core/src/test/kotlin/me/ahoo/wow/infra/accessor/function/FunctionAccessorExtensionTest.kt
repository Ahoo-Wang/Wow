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

class FunctionAccessorExtensionTest {

    @Test
    fun `should resolve declaring class from extension receiver`() {
        ExtensionFunctionReceiver::extensionGreeting.declaringClass.assert()
            .isEqualTo(ExtensionFunctionReceiver::class)
    }

    @Test
    fun `should resolve declaring class from member receiver`() {
        ExtensionFunctionReceiver::memberGreeting.declaringClass.assert()
            .isEqualTo(ExtensionFunctionReceiver::class)
    }

    @Test
    fun `should invoke extension function with receiver target`() {
        val accessor = SimpleFunctionAccessor<ExtensionFunctionReceiver, String>(
            ExtensionFunctionReceiver::extensionGreeting
        )

        accessor.invoke(ExtensionFunctionReceiver(), emptyArray()).assert()
            .isEqualTo("member")
    }

    @Test
    fun `invoke1 should invoke extension function with receiver target`() {
        val accessor = SimpleFunctionAccessor<ExtensionFunctionReceiver, String>(
            ExtensionFunctionReceiver::extensionEcho
        )

        accessor.invoke1(ExtensionFunctionReceiver(), "wow").assert()
            .isEqualTo("member wow")
    }
}

private class ExtensionFunctionReceiver {
    private val greeting = "member"

    fun memberGreeting(): String = greeting
}

private fun ExtensionFunctionReceiver.extensionGreeting(): String = memberGreeting()

private fun ExtensionFunctionReceiver.extensionEcho(value: String): String = "${memberGreeting()} $value"
