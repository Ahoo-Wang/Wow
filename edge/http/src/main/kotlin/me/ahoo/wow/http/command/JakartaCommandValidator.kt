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

package me.ahoo.wow.http.command

import jakarta.validation.ConstraintViolation
import jakarta.validation.Validator
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.CommandValidator

/**
 * The Jakarta Bean Validation adapter of [CommandValidator] (decision D106): the command body's constraints, checked
 * by [validator], become the [Violation]s of `wow.command.invalid` (400).
 *
 * A violation's path is the property path (`items[0].quantity`, empty for the body itself), its code the simple name
 * of the constraint annotation (`NotBlank`, `Min`), its message the interpolated one.
 *
 * `jakarta.validation-api` is a compile-only dependency of this module: the host constructs this validator only when
 * the application provides a [Validator] (a Bean Validation provider on its classpath).
 */
class JakartaCommandValidator(private val validator: Validator) : CommandValidator {
    override fun validate(type: CommandType, body: Any): List<Violation> =
        validator.validate(body).map(::violationOf).sortedWith(compareBy({ it.path }, { it.code }))

    /** The Jakarta contract gives every violation a path, a descriptor with its annotation, and a message. */
    private fun violationOf(violation: ConstraintViolation<Any>): Violation = Violation(
        path = violation.propertyPath.toString(),
        code = violation.constraintDescriptor.annotation.annotationClass.java.simpleName,
        message = violation.message,
    )
}
