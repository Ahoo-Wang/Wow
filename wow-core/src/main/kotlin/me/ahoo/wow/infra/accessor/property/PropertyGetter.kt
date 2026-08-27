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

/**
 * Functional interface for getting property values from objects.
 * Provides a unified way to access properties regardless of whether they are
 * static values, computed properties, or actual object properties.
 *
 * @param T the type of the target object
 * @param V the type of the property value
 */
fun interface PropertyGetter<in T, V> {
    /**
     * Gets the property value from the specified receiver object.
     *
     * @param receiver the object from which to get the property value
     * @return the property value
     */
    operator fun get(receiver: T): V
}

/**
 * PropertyGetter implementation that always returns a static (constant) value.
 * This getter ignores the receiver object and always returns the same pre-configured value.
 *
 * @param T the type of the target object (not used)
 * @param V the type of the property value
 * @property value the static value to return
 */
class StaticPropertyGetter<T, V>(
    val value: V
) : PropertyGetter<T, V> {
    /**
     * Returns the static value, ignoring the receiver.
     *
     * @param receiver the receiver object (ignored)
     * @return the static value
     */
    override fun get(receiver: T): V = value
}

/**
 * PropertyGetter implementation that accesses a Kotlin property reflectively.
 * This getter uses Kotlin reflection to access property values, automatically
 * ensuring the property is accessible for private/protected properties.
 *
 * @param T the type of the target object
 * @param V the type of the property value
 * @property property the Kotlin property to access
 */
class SimplePropertyGetter<T, V>(
    val property: KProperty1<T, V>
) : PropertyGetter<T, V> {
    /**
     * Initialization block that ensures the property is accessible for reflection.
     * This automatically makes private, protected, or package-private properties accessible.
     */
    init {
        property.ensureAccessible()
    }

    /**
     * Gets the property value from the receiver using reflection.
     *
     * @param receiver the object from which to get the property value
     * @return the property value
     */
    override fun get(receiver: T): V = property.get(receiver)
}
