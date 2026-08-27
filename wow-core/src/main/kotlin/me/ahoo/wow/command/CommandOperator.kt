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

package me.ahoo.wow.command

import me.ahoo.wow.api.messaging.Header

/**
 * Provides utility functions and extensions for managing command operators in message headers.
 * This object allows setting and retrieving operator information from command message headers,
 * which is useful for tracking who initiated a command.
 */
object CommandOperator {
    private const val OPERATOR_HEADER = "command_operator"

    /**
     * Retrieves the operator from the command header.
     *
     * @return The operator string if present, null otherwise.
     */
    val Header.operator: String?
        get() {
            return this[OPERATOR_HEADER]
        }

    /**
     * Retrieves the operator from the command header, throwing an exception if not present.
     *
     * @return The operator string.
     * @throws IllegalStateException if the operator is not set in the header.
     */
    val Header.requiredOperator: String
        get() {
            return checkNotNull(operator) { "operator is required!" }
        }

    /**
     * Creates a new header with the specified operator added.
     *
     * @param operator The operator string to add to the header.
     * @return A new Header instance with the operator set.
     */
    fun Header.withOperator(operator: String): Header = this.with(OPERATOR_HEADER, operator)
}
