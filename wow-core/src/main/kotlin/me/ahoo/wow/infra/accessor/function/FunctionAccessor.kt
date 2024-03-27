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

import me.ahoo.wow.infra.accessor.method.FastInvoke
import java.lang.reflect.Method
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

/**
 * Function Accessor .
 * @author ahoo wang
 */
interface FunctionAccessor<T, out R> {
    @Suppress("UNCHECKED_CAST")
    val targetType: Class<T>
        get() = method.declaringClass as Class<T>
    val method: Method
        get() = function.javaMethod!!
    val function: KFunction<*>

    fun invoke(target: T, args: Array<Any?> = emptyArray<Any?>()): R {
        return FastInvoke.safeInvoke(method, target, args)
    }
}
