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

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException

internal class DefaultConstructorAccessorTest {
    @Test
    fun constructor() {
        val constructorAccessor = DefaultConstructorAccessor(MockConstructor.CTOR)
        assertThat(constructorAccessor.invoke(), notNullValue())
    }

    @Test
    fun constructorWhenIllegalAccess() {
        val constructorAccessor = DefaultConstructorAccessor(MockConstructorWhenIllegalAccess.CTOR)
        MockConstructorWhenIllegalAccess.CTOR.isAccessible = false
        Assertions.assertThrows(IllegalAccessException::class.java) { constructorAccessor.invoke() }
    }

    @Test
    fun constructorWhenIllegalArgument() {
        val constructorAccessor = DefaultConstructorAccessor(MockConstructorWhenIllegalArgument.CTOR)
        Assertions.assertThrows(IllegalArgumentException::class.java) { constructorAccessor.invoke() }
    }

    @Test
    fun constructorWhenError() {
        val constructorAccessor = DefaultConstructorAccessor(MockConstructorWhenError.CTOR)
        Assertions.assertThrows(InvocationTargetException::class.java) { constructorAccessor.invoke() }
    }

    @Suppress("UtilityClassWithPublicConstructor")
    class MockConstructor {
        companion object {
            val CTOR: Constructor<MockConstructor> = MockConstructor::class.java.getConstructor()
        }
    }

    class MockConstructorWhenIllegalAccess {
        private constructor()

        companion object {
            val CTOR: Constructor<MockConstructorWhenIllegalAccess> =
                MockConstructorWhenIllegalAccess::class.java.getDeclaredConstructor()
        }
    }

    @Suppress("UtilityClassWithPublicConstructor")
    class MockConstructorWhenIllegalArgument {
        init {
            throw IllegalArgumentException()
        }

        companion object {
            val CTOR: Constructor<MockConstructorWhenIllegalArgument> =
                MockConstructorWhenIllegalArgument::class.java.getConstructor()
        }
    }

    @Suppress("UtilityClassWithPublicConstructor", "TooGenericExceptionThrown")
    class MockConstructorWhenError {
        init {
            throw Error()
        }

        companion object {
            var CTOR: Constructor<MockConstructorWhenError> = MockConstructorWhenError::class.java.getConstructor()
        }
    }
}
