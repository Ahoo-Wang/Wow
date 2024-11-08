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

val TestValidator: Validator = Validation.byDefaultProvider()
    .configure()
    .messageInterpolator(ParameterMessageInterpolator())
    .buildValidatorFactory()
    .validator

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
