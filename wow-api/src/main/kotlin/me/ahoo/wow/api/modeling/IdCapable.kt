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

package me.ahoo.wow.api.modeling

interface IdCapable<ID : Any> {
    /**
     * The unique identifier of this entity.
     *
     * @return A non-null identifier of type [ID] that uniquely identifies this entity within
     *         its context. The value should be stable and not change during the entity's lifetime.
     */
    val id: ID
}

/**
 * Convenience type alias for entities with string-based identifiers.
 *
 * This is the most common case, typically using UUIDs or other string formats for identity.
 * Example usage:
 * ```kotlin
 * data class User(override val id: String) : StringIdCapable
 * ```
 */
typealias StringIdCapable = IdCapable<String>
