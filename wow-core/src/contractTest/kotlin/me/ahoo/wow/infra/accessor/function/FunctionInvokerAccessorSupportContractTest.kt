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
import me.ahoo.wow.infra.invoker.FunctionInvoker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FunctionInvokerAccessorSupportContractTest {

    @Test
    fun `member function should invoke through instance invoker`() {
        val accessor = SimpleFunctionAccessor<AccessorReceiver, String>(AccessorReceiver::memberEcho)
        val receiver = AccessorReceiver("hello")

        accessor.invoke(receiver, arrayOf("wow")).assert().isEqualTo("hello wow")
        accessor.invoke1(receiver, "codex").assert().isEqualTo("hello codex")
    }

    @Test
    fun `top level function should invoke through receiverless static invoker`() {
        val accessor = SimpleFunctionAccessor<Any?, String>(::topLevelEcho)

        accessor.invoke(null, arrayOf("wow")).assert().isEqualTo("top wow")
        accessor.invoke1(null, "codex").assert().isEqualTo("top codex")
    }

    @Test
    fun `extension function should prepend receiver for receiverless static invoker`() {
        val receiver = AccessorReceiver("hello")
        val greetingAccessor = SimpleFunctionAccessor<AccessorReceiver, String>(
            AccessorReceiver::extensionGreeting
        )
        val echoAccessor = SimpleFunctionAccessor<AccessorReceiver, String>(
            AccessorReceiver::extensionEcho
        )

        greetingAccessor.invoke(receiver, emptyArray()).assert().isEqualTo("hello")
        echoAccessor.invoke1(receiver, "wow").assert().isEqualTo("hello wow")
    }

    @Test
    fun `unsupported invoker should fail fast`() {
        val invoker = object : FunctionInvoker {
            override fun parameterCount(): Int = 0
        }

        assertThrows<IllegalStateException> {
            invoker.invokeFunction(::topLevelEcho, null, emptyArray())
        }.message.assert().contains("Unsupported function invoker")

        assertThrows<IllegalStateException> {
            invoker.invokeFunction1(::topLevelEcho, null, "wow")
        }.message.assert().contains("Unsupported function invoker")
    }
}

private class AccessorReceiver(
    private val greeting: String
) {
    fun memberEcho(value: String): String = "$greeting $value"

    fun greeting(): String = greeting
}

private fun topLevelEcho(value: String): String = "top $value"

private fun AccessorReceiver.extensionGreeting(): String = greeting()

private fun AccessorReceiver.extensionEcho(value: String): String = "${greeting()} $value"
