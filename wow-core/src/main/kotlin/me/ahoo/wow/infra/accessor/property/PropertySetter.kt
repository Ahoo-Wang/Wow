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

package me.ahoo.wow.infra.accessor.property

import me.ahoo.wow.infra.accessor.field.FieldSetter
import me.ahoo.wow.infra.accessor.method.MethodAccessor

fun interface PropertySetter<in T, in V> {
    operator fun set(target: T, value: V)
}

class FieldPropertySetter<in T, in V>(private val fieldSetter: FieldSetter<T, V>) : PropertySetter<T, V> {
    override fun set(target: T, value: V) {
        fieldSetter[target] = value
    }
}

class MethodPropertySetter<in T, in V>(private val methodAccessor: MethodAccessor<T, V>) : PropertySetter<T, V> {
    override fun set(target: T, value: V) {
        methodAccessor.invoke(target, arrayOf(value))
    }
}
