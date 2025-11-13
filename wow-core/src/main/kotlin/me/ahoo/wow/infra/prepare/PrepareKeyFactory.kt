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

package me.ahoo.wow.infra.prepare

/**
 * Factory interface for creating PrepareKey instances.
 * Implementations of this interface provide the mechanism to create key preparation
 * services for different types of values and named contexts.
 *
 * PrepareKeyFactory is typically used in dependency injection frameworks to provide
 * PrepareKey instances to components that need uniqueness guarantees.
 */
interface PrepareKeyFactory {
    /**
     * Creates a PrepareKey instance for the specified name and value type.
     * The created PrepareKey will handle preparation operations for keys with values of type V.
     *
     * @param V the type of values that will be prepared with the keys
     * @param name the name identifier for this PrepareKey (used for naming and configuration)
     * @param valueClass the Java class representing the value type V
     * @return a new PrepareKey instance configured for the specified parameters
     *
     * @sample
     * ```
     * val factory = MyPrepareKeyFactory()
     * val usernameKey = factory.create("username", String::class.java)
     * val emailKey = factory.create("email", String::class.java)
     * ```
     */
    fun <V : Any> create(
        name: String,
        valueClass: Class<V>
    ): PrepareKey<V>
}
