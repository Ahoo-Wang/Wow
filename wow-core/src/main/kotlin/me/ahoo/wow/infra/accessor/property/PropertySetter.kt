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
import kotlin.reflect.KMutableProperty1

/**
 * Functional interface for setting property values on objects.
 * Provides a unified way to modify properties using a consistent API.
 *
 * @param T the type of the target object
 * @param V the type of the property value
 */
fun interface PropertySetter<in T, in V> {
    /**
     * Sets the property value on the specified target object.
     *
     * @param target the object on which to set the property value
     * @param value the new property value
     */
    operator fun set(
        target: T,
        value: V
    )
}

/**
 * PropertySetter implementation that modifies a Kotlin mutable property reflectively.
 * This setter uses Kotlin reflection to set property values, automatically
 * ensuring the property is accessible for private/protected properties.
 *
 * @param T the type of the target object
 * @param V the type of the property value
 * @property property the Kotlin mutable property to modify
 */
class SimplePropertySetter<T, V>(
    private val property: KMutableProperty1<T, V>
) : PropertySetter<T, V> {
    /**
     * Initialization block that ensures the property is accessible for reflection.
     * This automatically makes private, protected, or package-private properties accessible.
     */
    init {
        property.ensureAccessible()
    }

    /**
     * Sets the property value on the target using reflection.
     *
     * @param target the object on which to set the property value
     * @param value the new property value
     */
    override fun set(
        target: T,
        value: V
    ) {
        property.set(target, value)
    }
}
