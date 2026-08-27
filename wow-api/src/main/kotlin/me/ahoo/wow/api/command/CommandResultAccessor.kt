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
 * Provides access to command execution results for tracking and idempotency purposes.
 *
 * This interface allows storing and retrieving the outcomes of command processing,
 * which is essential for:
 * - Implementing idempotent command handling
 * - Caching command results
 * - Auditing command executions
 * - Supporting distributed command processing
 *
 * Results are stored as key-value pairs where keys are unique identifiers
 * and values can be any serializable object.
 *
 * @see CommandMessage for the command structure
 *
 * Example usage:
 * ```kotlin
 * class CommandProcessor(private val resultAccessor: CommandResultAccessor) {
 *     fun processCommand(command: CommandMessage<*>): Any? {
 *         val result = resultAccessor.getCommandResult<Any>(command.commandId)
 *         if (result != null) {
 *             return result // Idempotent: return cached result
 *         }
 *         val newResult = executeCommand(command)
 *         resultAccessor.setCommandResult(command.commandId, newResult)
 *         return newResult
 *     }
 * }
 * ```
 */
interface CommandResultAccessor {
    /**
     * Stores the result of a command execution for later retrieval.
     *
     * @param key A unique identifier for the command result (typically the command ID)
     * @param value The result value to store. Can be any object that needs to be preserved
     *              for idempotency or auditing purposes.
     *
     * @throws IllegalArgumentException if the key is null or empty
     * @throws IllegalStateException if the result cannot be stored due to storage limitations
     */
    fun setCommandResult(
        key: String,
        value: Any
    )

    /**
     * Retrieves a previously stored command result by its key.
     *
     * @param R The expected type of the result value
     * @param key The unique identifier for the command result to retrieve
     * @return The stored result cast to type R, or null if no result exists for the key
     *
     * @throws ClassCastException if the stored value cannot be cast to type R
     * @throws IllegalArgumentException if the key is null or empty
     */
    fun <R> getCommandResult(key: String): R?

    /**
     * Retrieves all stored command results as a read-only map.
     *
     * @return An immutable map containing all command result key-value pairs.
     *         Returns an empty map if no results are stored.
     */
    fun getCommandResult(): Map<String, Any>
}
