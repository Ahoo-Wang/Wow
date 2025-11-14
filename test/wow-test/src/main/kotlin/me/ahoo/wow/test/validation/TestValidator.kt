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

package me.ahoo.wow.test.validation

import jakarta.validation.Validation
import jakarta.validation.Validator
import me.ahoo.wow.api.command.validation.CommandValidator
import me.ahoo.wow.command.CommandValidationException.Companion.toCommandValidationException
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator

/**
 * A pre-configured Jakarta Bean Validation [Validator] instance for testing purposes.
 *
 * This validator is configured with Hibernate Validator's [ParameterMessageInterpolator]
 * to support parameterized validation messages. It is used by the [validate] extension function
 * to perform validation on objects during unit tests.
 *
 * @see validate
 */
val TestValidator: Validator =
    Validation
        .byDefaultProvider()
        .configure()
        .messageInterpolator(ParameterMessageInterpolator())
        .buildValidatorFactory()
        .validator

/**
 * Validates this object using Jakarta Bean Validation and throws an exception if validation fails.
 *
 * This extension function performs validation on the receiver object using the [TestValidator].
 * If the object implements [CommandValidator], it first calls the custom validation logic.
 * Then, it validates the object against Jakarta Bean Validation constraints.
 * If any constraint violations are found, it throws a [CommandValidationException].
 *
 * @param C The type of the object being validated, must be a non-null type.
 * @receiver The object to validate.
 * @return The same object if validation passes.
 * @throws CommandValidationException If validation fails due to constraint violations.
 *
 * @sample
 * ```
 * data class TestCommand(@NotBlank val name: String = "")
 * ```
 *
 * fun testValidation() {
 *     val validCommand = TestCommand("valid")
 *     val result = validCommand.validate() // Returns the command if valid
 *
 *     val invalidCommand = TestCommand("") // Empty name violates @NotBlank
 *     try {
 *         invalidCommand.validate() // Throws CommandValidationException
 *     } catch (e: CommandValidationException) {
 *         // Handle validation error
 *     }
 * }
 */
fun <C : Any> C.validate(): C {
    if (this is CommandValidator) {
        this.validate()
    }
    val constraintViolations = TestValidator.validate(this)
    if (constraintViolations.isNotEmpty()) {
        throw constraintViolations.toCommandValidationException(this)
    }
    return this
}
