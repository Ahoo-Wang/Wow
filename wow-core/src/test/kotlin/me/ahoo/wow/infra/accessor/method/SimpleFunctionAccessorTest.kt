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
package me.ahoo.wow.infra.accessor.method

import me.ahoo.wow.infra.accessor.ensureAccessible
import me.ahoo.wow.infra.accessor.function.SimpleFunctionAccessor
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.isAccessible

internal class SimpleFunctionAccessorTest {
    @Test
    fun invoke() {
        val methodAccessor = SimpleFunctionAccessor<MockMethod, Unit>(MockMethod.INVOKE_FUNCTION)
        methodAccessor.invoke(MockMethod())
        assertThat(methodAccessor.method.declaringClass, equalTo(MockMethod::class.java))
    }

    @Test
    fun invokeWhenIllegalAccessException() {
        val methodAccessor =
            SimpleFunctionAccessor<MockMethodWhenIllegalAccess, Unit>(MockMethodWhenIllegalAccess.INVOKE_FUNCTION)
        MockMethodWhenIllegalAccess.INVOKE_FUNCTION.isAccessible = false
        Assertions.assertThrows(IllegalAccessException::class.java) {
            methodAccessor.invoke(
                MockMethodWhenIllegalAccess(),
            )
        }
    }

    @Test
    fun invokeWhenIllegalStateException() {
        val methodAccessor = SimpleFunctionAccessor<MockMethodWhenIllegalStateException, Unit>(
            MockMethodWhenIllegalStateException.INVOKE_FUNCTION,
        )
        Assertions.assertThrows(IllegalStateException::class.java) {
            methodAccessor.invoke(
                MockMethodWhenIllegalStateException(),
            )
        }
    }

    @Test
    fun invokeWhenError() {
        val methodAccessor =
            SimpleFunctionAccessor<MockMethodWhenError, Unit>(MockMethodWhenError.INVOKE_FUNCTION)
        Assertions.assertThrows(InvocationTargetException::class.java) { methodAccessor.invoke(MockMethodWhenError()) }
    }

    @Test
    fun staticInvoke() {
        MockMethod.STATIC_METHOD.ensureAccessible()
        FastInvoke.safeInvoke<Void>(MockMethod.STATIC_METHOD, null, kotlin.emptyArray<Any>())
    }

    @Test
    fun invokeWithArg() {
        val methodAccessor = SimpleFunctionAccessor<MockMethodWithArg, Unit>(MockMethodWithArg.INVOKE_FUNCTION)
        val arrayArg: Array<Any?> = Array(2) {
            "arg"
        }
        methodAccessor.invoke(MockMethodWithArg(), arrayArg)
    }

    class MockMethod {
        private fun invoke() = Unit

        companion object {
            val INVOKE_FUNCTION: KFunction<*> = MockMethod::invoke
            val STATIC_METHOD: Method = MockMethod::class.java.getDeclaredMethod("staticInvoke")

            @Suppress("UnusedPrivateMember")
            @JvmStatic
            private fun staticInvoke() = Unit
        }
    }

    class MockMethodWhenIllegalAccess {
        private fun invoke() = Unit

        companion object {
            val INVOKE_FUNCTION: KFunction<*> = MockMethodWhenIllegalAccess::invoke
        }
    }

    class MockMethodWhenIllegalStateException {
        @Suppress("UseCheckOrError")
        private fun invoke() {
            throw IllegalStateException()
        }

        companion object {
            val INVOKE_FUNCTION: KFunction<*> = MockMethodWhenIllegalStateException::invoke
        }
    }

    class MockMethodWhenError {
        @Suppress("TooGenericExceptionThrown")
        private fun invoke() {
            throw Error()
        }

        companion object {
            val INVOKE_FUNCTION: KFunction<*> = MockMethodWhenError::invoke
        }
    }

    class MockMethodWithArg {
        @Suppress("UnusedPrivateMember")
        fun invoke(arg1: String, arg2: String) = Unit

        companion object {
            val INVOKE_FUNCTION: KFunction<*> = MockMethodWithArg::invoke
        }
    }
}
