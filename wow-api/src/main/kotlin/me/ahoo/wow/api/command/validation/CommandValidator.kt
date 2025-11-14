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

package me.ahoo.wow.api.command.validation

/**
 * Functional interface for validating command payloads before execution.
 *
 * Commands implementing this interface can perform custom validation logic on their
 * payload data. Validation ensures that commands contain valid, consistent data before
 * being processed by aggregates, preventing invalid state changes and improving system reliability.
 *
 * The validation is typically executed early in the command processing pipeline,
 * allowing for fast failure of invalid commands.
 *
 * @see me.ahoo.wow.command.CommandValidationException thrown when validation fails
 * @see me.ahoo.wow.command.DefaultCommandGateway for where validation is typically invoked
 *
 * Example usage:
 * ```kotlin
 * data class CreateUserCommand(
 *     val email: String,
 *     val password: String
 * ) : CommandValidator {
 *
 *     override fun validate() {
 *         require(email.isNotBlank()) { "Email cannot be blank" }
 *         require(password.length >= 8) { "Password must be at least 8 characters" }
 *         require(email.contains("@")) { "Email must be valid format" }
 *     }
 * }
 * ```
 *
 * @throws me.ahoo.wow.command.CommandValidationException when validation rules are violated
 * @throws IllegalArgumentException for invalid input parameters
 * @throws IllegalStateException for invalid object state
 */
fun interface CommandValidator {
    /**
     * Validates the command payload for correctness and consistency.
     *
     * This method should check all business rules and constraints applicable to the command.
     * If validation fails, it should throw an appropriate exception with a descriptive message.
     *
     * Validation should be:
     * - Fast and lightweight (avoid expensive operations)
     * - Deterministic (same input always produces same result)
     * - Comprehensive (cover all critical validation rules)
     *
     * @throws me.ahoo.wow.command.CommandValidationException when business validation rules are violated
     * @throws IllegalArgumentException when input parameters are invalid
     * @throws IllegalStateException when the command is in an invalid state
     */
    fun validate()
}
