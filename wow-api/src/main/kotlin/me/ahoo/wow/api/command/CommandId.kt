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
 * Represents a unique identifier for a command within the Wow framework's command handling system.
 *
 * This interface ensures that each command can be uniquely identified, which is essential for:
 * - Maintaining idempotency in command processing
 * - Tracking and correlating commands across distributed systems
 * - Preventing duplicate command execution
 * - Debugging and auditing command flows
 *
 * @property commandId A unique string identifier for the command. Implementations should generate
 *                     globally unique values, typically using UUIDs or similar mechanisms.
 *
 * @see CommandMessage for the complete command message interface that includes this identifier
 *
 * Example usage:
 * ```kotlin
 * class CreateUserCommand(override val commandId: String = UUID.randomUUID().toString()) : CommandId {
 *     // command payload
 * }
 * ```
 */
interface CommandId {
    /**
     * A unique identifier for this command instance.
     *
     * @return A non-empty string that uniquely identifies this command. The format and generation
     *         strategy is implementation-dependent but should ensure global uniqueness.
     */
    val commandId: String
}
