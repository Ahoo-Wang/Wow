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
 * Provides methods to access and manipulate the results of a command execution. This interface is designed to store and retrieve command results, which can be useful for tracking the outcomes of commands
 *  and ensuring idempotency.
 */
interface CommandResultAccessor {
    /**
     * Sets the result of a command execution with the specified key and value.
     *
     * @param key The unique identifier for the command result to set.
     * @param value The value to be stored as the result of the command execution.
     */
    fun setCommandResult(key: String, value: Any)

    /**
     * Retrieves the result of a command execution based on the provided key.
     *
     * @param key The unique identifier for the command result to retrieve.
     * @return The result of the command execution associated with the given key, or null if no result is found.
     */
    fun <R> getCommandResult(key: String): R?

    /**
     * Retrieves the results of all command executions.
     *
     * @return A map containing the keys and their corresponding results of the command executions.
     */
    fun getCommandResult(): Map<String, Any>
}
