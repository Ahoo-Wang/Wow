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

package me.ahoo.wow.http.problem

import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.wire.WireNames
import org.springframework.context.MessageSourceResolvable
import org.springframework.validation.Errors
import org.springframework.validation.FieldError
import org.springframework.validation.method.ParameterErrors
import org.springframework.validation.method.ParameterValidationResult
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.method.annotation.HandlerMethodValidationException

/**
 * The violations of a request to the application's own route that failed validation (#6591), in the shape a command's
 * validation answers (`JakartaCommandValidator`), so that a client reads one shape: each violation's path, its code,
 * the constraint's name on the wire (`NotBlank` is `not-blank`; [INVALID] for an error that names no constraint), and
 * its message; by path, then code.
 *
 * - A `@Valid` body's ([WebExchangeBindException]): a field's path in the body (`items[0].quantity`), empty for the
 *   body itself (a constraint of its class).
 * - A method's constraints ([HandlerMethodValidationException]): a parameter's name (a query parameter, a header, a
 *   path variable), with its element's index or key when the constraint is on an element of it (`ids[1]`); an object
 *   parameter's (`@RequestBody`) fields as a `@Valid` body's, under its element's index or key the same way; empty for
 *   a constraint across the parameters.
 */
internal object RequestViolations {
    /** The code of an error that names no constraint. */
    const val INVALID: String = "invalid"

    fun of(failure: WebExchangeBindException): List<Violation> = sorted(errorsOf(failure, ""))

    fun of(failure: HandlerMethodValidationException): List<Violation> = sorted(
        failure.parameterValidationResults.flatMap(::violationsOf) +
            failure.crossParameterValidationResults.map { violationOf("", it) },
    )

    private fun violationsOf(result: ParameterValidationResult): List<Violation> {
        val element = elementOf(result)
        if (result is ParameterErrors) {
            return errorsOf(result, element)
        }
        val path = result.methodParameter.parameterName.orEmpty() + element
        return result.resolvableErrors.map { violationOf(path, it) }
    }

    /** The errors of an object, its fields' under [prefix]. */
    private fun errorsOf(errors: Errors, prefix: String): List<Violation> = errors.allErrors.map { error ->
        val field = (error as? FieldError)?.field
        val path = when {
            field == null -> prefix
            prefix.isEmpty() -> field
            else -> "$prefix.$field"
        }
        violationOf(path, error)
    }

    /** `[index]` or `[key]` of the element of a container [result] is about; empty for the parameter itself. */
    private fun elementOf(result: ParameterValidationResult): String =
        (result.containerIndex ?: result.containerKey)?.let { "[$it]" }.orEmpty()

    private fun violationOf(path: String, error: MessageSourceResolvable): Violation =
        Violation.of(path, codeOf(error), error.defaultMessage.orEmpty())

    /** The constraint's name on the wire: the most general of the error's codes (`NotBlank` of `NotBlank.item.name`). */
    private fun codeOf(error: MessageSourceResolvable): String =
        error.codes?.lastOrNull()?.let(WireNames::of) ?: INVALID

    private fun sorted(violations: List<Violation>): List<Violation> =
        violations.sortedWith(compareBy({ it.path }, { it.code }))
}
