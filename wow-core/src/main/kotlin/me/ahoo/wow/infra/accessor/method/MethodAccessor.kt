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

import me.ahoo.wow.infra.accessor.Accessor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * MethodAccessor .
 * @author ahoo wang
 */
interface MethodAccessor<T, out R> {
    @Suppress("UNCHECKED_CAST")
    val targetType: Class<T>
        get() = method.declaringClass as Class<T>
    val method: Method

    fun invoke(target: T, args: Array<Any?> = emptyArray<Any?>()): R {
        return Companion.invoke(method, target, args)
    }

    companion object {

        @Suppress("SwallowedException")
        @JvmStatic
        fun <T, R> invoke(method: Method, target: T, args: Array<Any?> = emptyArray<Any?>()): R {
            try {
                @Suppress("UNCHECKED_CAST")
                return FastInvoke.invoke(method, target, args) as R
            } catch (e: InvocationTargetException) {
                if (e.targetException is RuntimeException) {
                    throw e.targetException
                } else {
                    throw e
                }
            }
        }

        @JvmStatic
        fun <R> invokeStatic(method: Method, args: Array<Any?> = emptyArray<Any?>()): R {
            return invoke(method, Accessor.STATIC, args)
        }
    }
}
