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

import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.infra.accessor.method.FastInvoke
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure

/**
 * Extension property that determines the declaring class of a Kotlin function.
 * This property handles both regular member functions and extension functions,
 * returning the appropriate declaring class.
 *
 * For member functions, it returns the class containing the function.
 * For extension functions, it returns the class being extended.
 * For top-level functions, it returns the declaring class of the underlying Java method.
 */
val KFunction<*>.declaringClass: KClass<*>
    get() {
        val parameter = instanceParameter ?: extensionReceiverParameter
        if (parameter != null) {
            return parameter.type.jvmErasure
        }
        return checkNotNull(javaMethod).declaringClass.kotlin
    }

/**
 * Interface for accessing and invoking Kotlin functions through reflection.
 * Provides a type-safe way to invoke functions with proper error handling and
 * integration with the Wow framework's naming conventions.
 *
 * @param T the type of the target object on which the function will be invoked
 * @param R the return type of the function
 * @author ahoo wang
 */
interface FunctionAccessor<T, out R> : Named {
    /**
     * The name of the function, inherited from the Named interface.
     * Returns the simple name of the underlying Kotlin function.
     */
    override val name: String
        get() = function.name

    /**
     * The Java Class representing the type of the target object.
     * This is derived from the declaring class of the function.
     */
    @Suppress("UNCHECKED_CAST")
    val targetType: Class<T>
        get() = function.declaringClass.java as Class<T>

    /**
     * The underlying Java Method object for this function.
     * This method is guaranteed to be non-null for accessible functions.
     */
    val method: Method
        get() = function.javaMethod!!

    /**
     * The Kotlin reflection KFunction representing this function.
     * Provides access to Kotlin-specific metadata and reflection capabilities.
     */
    val function: KFunction<*>

    /**
     * Invokes the function on the specified target object with the given arguments.
     * Uses FastInvoke.safeInvoke for proper exception handling and performance.
     *
     * @param target the object on which to invoke the function
     * @param args the arguments to pass to the function (empty array by default)
     * @return the result of the function invocation
     * @throws Throwable if the function invocation fails or throws an exception
     *
     * Example usage:
     * ```
     * val accessor = SimpleFunctionAccessor<MyClass, String>(MyClass::getName)
     * val instance = MyClass()
     * val result = accessor.invoke(instance) // calls instance.getName()
     * ```
     */
    fun invoke(
        target: T,
        args: Array<Any?> = emptyArray<Any?>()
    ): R = FastInvoke.safeInvoke(method, target, args)
}
