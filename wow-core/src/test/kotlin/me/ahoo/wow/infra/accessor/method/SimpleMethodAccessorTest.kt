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
import me.ahoo.wow.infra.accessor.method.MethodAccessor.Companion.invokeStatic
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal class SimpleMethodAccessorTest {
    @Test
    fun invoke() {
        val methodAccessor = SimpleMethodAccessor<MockMethod, Unit>(MockMethod.METHOD)
        methodAccessor.invoke(MockMethod())
        assertThat(methodAccessor.method.declaringClass, equalTo(MockMethod::class.java))
    }

    @Test
    fun invokeWhenIllegalAccessException() {
        val methodAccessor = SimpleMethodAccessor<MockMethodWhenIllegalAccess, Unit>(MockMethodWhenIllegalAccess.METHOD)
        MockMethodWhenIllegalAccess.METHOD.isAccessible = false
        Assertions.assertThrows(IllegalAccessException::class.java) { methodAccessor.invoke(MockMethodWhenIllegalAccess()) }
    }

    @Test
    fun invokeWhenIllegalStateException() {
        val methodAccessor = SimpleMethodAccessor<MockMethodWhenIllegalStateException, Unit>(
            MockMethodWhenIllegalStateException.METHOD,
        )
        Assertions.assertThrows(IllegalStateException::class.java) {
            methodAccessor.invoke(
                MockMethodWhenIllegalStateException(),
            )
        }
    }

    @Test
    fun invokeWhenError() {
        val methodAccessor = SimpleMethodAccessor<MockMethodWhenError, Unit>(MockMethodWhenError.METHOD)
        Assertions.assertThrows(InvocationTargetException::class.java) { methodAccessor.invoke(MockMethodWhenError()) }
    }

    @Test
    fun invokeStatic() {
        MockMethod.STATIC_METHOD.ensureAccessible()
        invokeStatic<Any>(MockMethod.STATIC_METHOD)
    }

    class MockMethod {
        private fun invoke() {}

        companion object {
            val METHOD: Method = MockMethod::class.java.getDeclaredMethod("invoke")
            val STATIC_METHOD: Method = MockMethod::class.java.getDeclaredMethod("staticInvoke")

            @JvmStatic
            private fun staticInvoke() {
            }
        }
    }

    class MockMethodWhenIllegalAccess {
        private fun invoke() {}

        companion object {
            val METHOD: Method = MockMethodWhenIllegalAccess::class.java.getDeclaredMethod("invoke")
        }
    }

    class MockMethodWhenIllegalStateException {
        private fun invoke() {
            throw IllegalStateException()
        }

        companion object {
            val METHOD: Method = MockMethodWhenIllegalStateException::class.java.getDeclaredMethod("invoke")
        }
    }

    class MockMethodWhenError {
        private fun invoke() {
            throw Error()
        }

        companion object {
            val METHOD: Method = MockMethodWhenError::class.java.getDeclaredMethod("invoke")
        }
    }
}
