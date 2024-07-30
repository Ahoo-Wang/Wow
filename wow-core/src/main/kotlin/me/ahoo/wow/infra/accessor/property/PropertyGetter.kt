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

import me.ahoo.wow.infra.accessor.ensureAccessible
import kotlin.reflect.KProperty1

fun interface PropertyGetter<in T, V> {
    operator fun get(receiver: T): V
}

class StaticPropertyGetter<T, V>(val value: V) : PropertyGetter<T, V> {

    override fun get(receiver: T): V {
        return value
    }
}

class SimplePropertyGetter<T, V>(val property: KProperty1<T, V>) : PropertyGetter<T, V> {
    init {
        property.ensureAccessible()
    }

    override fun get(receiver: T): V {
        return property.get(receiver)
    }
}
