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

import me.ahoo.wow.infra.accessor.method.FastInvoke
import java.lang.reflect.Constructor

/**
 * Interface for accessing and invoking constructors through reflection.
 * Provides a type-safe way to create new instances of classes using their constructors
 * with proper error handling and performance optimization.
 *
 * @param T the type of object that will be created by the constructor
 */
interface ConstructorAccessor<T : Any> {
    /**
     * The underlying Java Constructor object for creating instances.
     * This constructor is used for reflection-based instantiation.
     */
    val constructor: Constructor<T>

    /**
     * Invokes the constructor with the specified arguments to create a new instance.
     * Uses FastInvoke.safeNewInstance for proper exception handling and performance.
     *
     * @param args the arguments to pass to the constructor (empty array by default)
     * @return a new instance of type T
     * @throws Throwable if the constructor invocation fails or throws an exception
     *
     * Example usage:
     * ```
     * val constructor = MyClass::class.java.getConstructor(String::class.java)
     * val accessor = DefaultConstructorAccessor(constructor)
     * val instance = accessor.invoke(arrayOf("initial value"))
     * ```
     */
    fun invoke(args: Array<Any?> = emptyArray<Any?>()): T = FastInvoke.safeNewInstance(constructor, args)
}
