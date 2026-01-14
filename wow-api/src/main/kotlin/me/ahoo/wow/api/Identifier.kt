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

import me.ahoo.wow.api.modeling.StringIdCapable

/**
 * Represents an identifiable entity with a unique identifier.
 *
 * This interface provides a contract for objects that need to be uniquely identifiable within a system.
 * Implementing classes must provide a string-based identifier that can be used for equality checks,
 * database operations, caching, and other scenarios requiring unique entity references.
 *
 * The identifier should be immutable and unique across all instances of the same type.
 * Common implementations include UUIDs, database primary keys, or domain-specific identifiers.
 *
 * @sample
 * ```
 * data class User(val name: String, override val id: String) : Identifier
 *
 * val user = User("Alice", "user-123")
 * println(user.id) // Output: user-123
 * ```
 */
interface Identifier : StringIdCapable {
    /**
     * Represents a unique identifier for the implementing entity.
     *
     * This identifier serves as the primary key for distinguishing between different instances
     * of the same type. It is used extensively in:
     * - Database operations for record identification
     * - State management and event sourcing
     * - Caching mechanisms
     * - Equality and hashing operations
     * - API endpoints and resource references
     *
     * The identifier must be:
     * - Unique across all instances of the implementing type
     * - Immutable once assigned
     * - Non-null and non-empty
     * - Suitable for use as a string key in maps and databases
     *
     * @return A string representation of the unique identifier.
     *         The format and generation strategy depend on the implementing class.
     */
    override val id: String
}
