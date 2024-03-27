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
package me.ahoo.wow.infra.accessor.field

import me.ahoo.wow.infra.accessor.Accessor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException

/**
 * FieldSetter .
 * @author ahoo wang
 */
@Deprecated("Use PropertyAccessor instead.")
interface FieldSetter<in T, in V> {
    val field: Field

    operator fun set(target: T, value: V) {
        Companion.set(field, target, value)
    }

    fun setStatic(value: V) {
        @Suppress("UNCHECKED_CAST")
        set(Accessor.STATIC as T, value)
    }

    companion object {
        fun <T, V> set(field: Field, target: T, value: V) {
            try {
                field[target] = value
            } catch (e: InvocationTargetException) {
                if (e.targetException is RuntimeException) {
                    throw e.targetException
                } else {
                    throw e
                }
            }
        }
    }
}
