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

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

/**
 * Utility object for creating property accessors from various sources.
 * Provides factory methods to convert static values, read-only properties, and mutable properties
 * into PropertyGetter and PropertySetter implementations.
 *
 * This object serves as the main entry point for creating property accessors in a consistent way.
 */
object PropertyDescriptor {
    /**
     * Converts a static value into a PropertyGetter that always returns the same value.
     * Useful for creating property accessors for constant or computed values.
     *
     * @param T the type of the target object (not used in static getters)
     * @param V the type of the property value
     * @return a PropertyGetter that always returns the static value
     *
     * Example usage:
     * ```
     * val staticGetter = "constant".toPropertyGetter<MyClass, String>()
     * val value = staticGetter.get(anyInstance) // always returns "constant"
     * ```
     */
    fun <T, V> V.toPropertyGetter(): PropertyGetter<T, V> = StaticPropertyGetter(this)

    /**
     * Converts a Kotlin read-only property into a PropertyGetter.
     * The resulting getter will access the property value from the target object.
     *
     * @param T the type of the target object
     * @param V the type of the property value
     * @return a PropertyGetter that accesses the property value
     *
     * Example usage:
     * ```
     * val propertyGetter = MyClass::name.toPropertyGetter()
     * val instance = MyClass("John")
     * val name = propertyGetter.get(instance) // returns "John"
     * ```
     */
    fun <T, V> KProperty1<T, V>.toPropertyGetter(): PropertyGetter<T, V> = SimplePropertyGetter(this)

    /**
     * Converts a Kotlin mutable property into a PropertySetter.
     * The resulting setter will modify the property value on the target object.
     *
     * @param T the type of the target object
     * @param V the type of the property value
     * @return a PropertySetter that modifies the property value
     *
     * Example usage:
     * ```
     * val propertySetter = MyClass::name.toPropertySetter()
     * val instance = MyClass("John")
     * propertySetter.set(instance, "Jane") // changes name to "Jane"
     * ```
     */
    fun <T, V> KMutableProperty1<T, V>.toPropertySetter(): PropertySetter<T, V> = SimplePropertySetter(this)
}
