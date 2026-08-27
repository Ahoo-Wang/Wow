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

package me.ahoo.wow.command.validation

import jakarta.validation.Validator
import me.ahoo.wow.command.CommandValidationException
import me.ahoo.wow.command.CommandValidationException.Companion.toCommandValidationException

/**
 * Validates a command body using the validator.
 *
 * This extension function performs validation on the command body and throws
 * a CommandValidationException if any constraint violations are found.
 *
 * @param C the type of the command body
 * @param commandBody the command body to validate
 * @return the validated command body (unchanged)
 * @throws CommandValidationException if validation fails
 * @see Validator.validate
 * @see CommandValidationException
 */
@Throws(CommandValidationException::class)
fun <C : Any> Validator.validateCommand(commandBody: C): C {
    val constraintViolations = validate(commandBody)
    if (constraintViolations.isNotEmpty()) {
        throw constraintViolations.toCommandValidationException(commandBody)
    }
    return commandBody
}
