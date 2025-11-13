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

package me.ahoo.wow.api

/**
 * Defines a contract for copy operations, allowing objects implementing this interface to create copies of themselves.
 *
 * This interface provides a type-safe way to duplicate objects, ensuring that modifications to the copy
 * do not affect the original instance. The generic parameter [SOURCE] represents the type of the source object,
 * which is concretized by implementing classes.
 *
 * Implementations should create deep copies where appropriate to prevent unintended side effects
 * when the copied object is modified.
 *
 * @param SOURCE The type of the source object, specified by the implementing class.
 *               This is a covariant generic parameter (declared with 'out') to allow more specific types
 *               in implementations while maintaining type safety.
 *
 * @sample
 * ```
 * data class Person(val name: String, val age: Int) : Copyable<Person> {
 *     override fun copy(): Person = Person(name, age)
 * }
 *
 * val original = Person("Alice", 30)
 * val copy = original.copy()
 * // Modifications to 'copy' don't affect 'original'
 * ```
 */
interface Copyable<out SOURCE> {
    /**
     * Creates and returns a copy of the current object.
     *
     * This method should produce a new instance that is functionally equivalent to the current object
     * but independent in terms of state modifications. The copy should be deep enough to prevent
     * shared mutable state between the original and the copy.
     *
     * @return A new instance of type [SOURCE] that represents a copy of this object.
     *         The returned object should be of the same type as the implementing class.
     */
    fun copy(): SOURCE
}
