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
package me.ahoo.wow.infra.accessor

import java.lang.reflect.AccessibleObject
import kotlin.reflect.KCallable
import kotlin.reflect.jvm.isAccessible

/**
 * Ensures that a Java reflection AccessibleObject (like Method, Field, Constructor) is accessible.
 * This method sets the accessible flag to true if it's not already set, allowing access
 * to private, protected, or package-private members.
 *
 * This is a convenience method that safely handles the accessibility check and setting.
 *
 * Example usage:
 * ```
 * val method = MyClass::class.java.getDeclaredMethod("privateMethod")
 * method.ensureAccessible() // Now accessible even if private
 * method.invoke(instance)
 * ```
 */
fun AccessibleObject.ensureAccessible() {
    if (!this.isAccessible) {
        this.isAccessible = true
    }
}

/**
 * Ensures that a Kotlin reflection KCallable (like KFunction, KProperty) is accessible.
 * This method sets the accessible flag to true if it's not already set, allowing access
 * to private, protected, or package-private members.
 *
 * This is a convenience method that safely handles the accessibility check and setting.
 *
 * Example usage:
 * ```
 * val property = MyClass::class.members.find { it.name == "privateProperty" } as KProperty1<MyClass, *>
 * property.ensureAccessible() // Now accessible even if private
 * val value = property.get(instance)
 * ```
 */
fun KCallable<*>.ensureAccessible() {
    if (!this.isAccessible) {
        this.isAccessible = true
    }
}
