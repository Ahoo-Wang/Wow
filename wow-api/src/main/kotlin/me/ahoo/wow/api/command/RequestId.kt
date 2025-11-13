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

package me.ahoo.wow.api.command

/**
 * Represents a unique identifier for a request within the Wow framework's command processing system.
 *
 * This interface ensures that each request can be uniquely identified, which is essential for:
 * - Maintaining request traceability across distributed systems
 * - Implementing request deduplication and idempotency
 * - Correlating requests with responses and events
 * - Debugging and monitoring request flows
 * - Supporting distributed tracing and logging
 *
 * @property requestId A unique string identifier for the request. Implementations should generate
 *                     globally unique values, typically using UUIDs or similar mechanisms.
 *
 * @see CommandMessage for the complete command message interface that includes this identifier
 *
 * Example usage:
 * ```kotlin
 * class UserRequest(override val requestId: String = UUID.randomUUID().toString()) : RequestId {
 *     // request payload
 * }
 * ```
 */
interface RequestId {
    /**
     * A unique identifier for this request instance.
     *
     * @return A non-empty string that uniquely identifies this request. The format and generation
     *         strategy is implementation-dependent but should ensure global uniqueness.
     */
    val requestId: String
}
