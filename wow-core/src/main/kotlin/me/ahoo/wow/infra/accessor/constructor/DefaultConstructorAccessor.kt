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

import me.ahoo.wow.infra.accessor.ensureAccessible
import java.lang.reflect.Constructor

/**
 * Default implementation of ConstructorAccessor that provides basic constructor invocation capabilities.
 * This class automatically ensures the constructor is accessible during initialization,
 * making it ready for reflection-based instantiation of private, protected, or package-private constructors.
 *
 * @param T the type of object that will be created by the constructor
 * @property constructor the Java constructor to be accessed
 */
class DefaultConstructorAccessor<T : Any>(
    override val constructor: Constructor<T>
) : ConstructorAccessor<T> {
    private val constructorInvoker: ConstructorInvoker<T>

    /**
     * Initialization block that ensures the constructor is accessible for reflection.
     * This automatically makes private, protected, or package-private constructors accessible.
     */
    init {
        constructor.ensureAccessible()
        constructorInvoker = ConstructorInvokerFactory.create(constructor)
    }

    override fun invoke(args: Array<out Any?>): T {
        @Suppress("UNCHECKED_CAST")
        return constructorInvoker.newInstance(args as Array<Any?>)
    }

    override fun newInstance0(): T {
        return constructorInvoker.newInstance0()
    }

    override fun newInstance1(arg: Any?): T {
        return constructorInvoker.newInstance1(arg)
    }

    override fun newInstance2(arg1: Any?, arg2: Any?): T {
        return constructorInvoker.newInstance2(arg1, arg2)
    }
}
