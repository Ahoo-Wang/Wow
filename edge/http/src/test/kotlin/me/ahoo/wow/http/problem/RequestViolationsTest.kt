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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.problem.ProblemMapper
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.http.cartManifest
import org.junit.jupiter.api.Test
import org.springframework.context.MessageSourceResolvable
import org.springframework.context.support.DefaultMessageSourceResolvable
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.MethodParameter
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.Errors
import org.springframework.validation.FieldError
import org.springframework.validation.ObjectError
import org.springframework.validation.method.MethodValidationResult
import org.springframework.validation.method.ParameterErrors
import org.springframework.validation.method.ParameterValidationResult
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.util.function.BiFunction

/**
 * #6591: a request to the application's own route that fails its constraints is `wow.request.invalid`, its violations
 * in a command's shape: the path, the constraint's name on the wire, the message.
 */
class RequestViolationsTest {
    data class Item(val name: String, val quantity: Int)

    /** An annotated route: a body, a query parameter, and a list of ids. */
    class Routes {
        @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
        fun route(item: Item, limit: Int, ids: List<String>): String = "routed"
    }

    private val method = Routes::class.java.getMethod("route", Item::class.java, Int::class.java, List::class.java)

    private fun parameter(index: Int) =
        MethodParameter(method, index).apply { initParameterNameDiscovery(DefaultParameterNameDiscoverer()) }

    /** Spring's errors of a Bean Validation constraint: its codes, the most general last. */
    private fun field(objectName: String, field: String, constraint: String, message: String) =
        FieldError(objectName, field, null, false, arrayOf("$constraint.$objectName.$field", constraint), null, message)

    private fun bodyErrors(): BeanPropertyBindingResult = BeanPropertyBindingResult(Item(" ", 0), "item").apply {
        addError(field("item", "quantity", "Min", "must be greater than or equal to 1"))
        addError(field("item", "name", "NotBlank", "must not be blank"))
        addError(ObjectError("item", arrayOf("ItemConsistent.item", "ItemConsistent"), null, "is not consistent"))
        // An error a Spring Validator rejects with no code, and no message.
        addError(ObjectError("item", null, null, null))
    }

    private val problems = FailureProblems(ErrorStatuses(cartManifest()))

    @Test
    fun `a @Valid body's errors are its fields' paths, the constraints' wire names and messages, by path and code`() {
        val problem = problems.problemOf(WebExchangeBindException(parameter(0), bodyErrors()))
        problem.status.assert().isEqualTo(400)
        problem.code.assert().isEqualTo(FrameworkCodes.REQUEST_INVALID)
        problem.title.assert().isEqualTo("Invalid request")
        problem.detail.assert().isEqualTo("The request failed validation; the violations list what it broke.")
        problem.retry.assert().isEqualTo(Retry.NONE)
        problem.violations.assert().containsExactly(
            Violation.of("", RequestViolations.INVALID, ""),
            Violation.of("", "item-consistent", "is not consistent"),
            Violation.of("name", "not-blank", "must not be blank"),
            Violation.of("quantity", "min", "must be greater than or equal to 1"),
        )
    }

    @Test
    fun `a method's constraints name its parameters, an element of one, an object's fields, and none across them`() {
        val limit = valueResult(
            parameter(1),
            0,
            listOf(resolvable("Min.limit", "Min", "must be greater than or equal to 1")),
            null,
            null,
            null,
        )
        val secondId = valueResult(
            parameter(2),
            "",
            listOf(resolvable("NotBlank.ids", "NotBlank", "must not be blank")),
            listOf("a", ""),
            1,
            null,
        )
        val keyedId = valueResult(
            parameter(2),
            "",
            listOf(resolvable("NotBlank.ids", "NotBlank", "must not be blank")),
            mapOf("first" to ""),
            null,
            "first",
        )
        val body = beanResult(null, null)
        val listedBody = beanResult(listOf(Item(" ", 0)), 0)
        val result = MethodValidationResult.create(
            Routes(),
            method,
            listOf(limit, secondId, keyedId, body, listedBody),
            listOf(resolvable("Sensible.route", "Sensible", "is not sensible")),
        )
        val problem = problems.problemOf(HandlerMethodValidationException(result))
        problem.code.assert().isEqualTo(FrameworkCodes.REQUEST_INVALID)
        problem.violations.map { it.path to it.code }.assert().containsExactly(
            "" to RequestViolations.INVALID,
            "" to "item-consistent",
            "" to "sensible",
            "[0]" to RequestViolations.INVALID,
            "[0]" to "item-consistent",
            "[0].name" to "not-blank",
            "[0].quantity" to "min",
            "ids[1]" to "not-blank",
            "ids[first]" to "not-blank",
            "limit" to "min",
            "name" to "not-blank",
            "quantity" to "min",
        )
    }

    @Test
    fun `a return value that fails its method's constraints is the server's own 500, its status alone`() {
        val returned = valueResult(
            MethodParameter(method, -1),
            "routed",
            listOf(resolvable("NotBlank.route", "NotBlank", "must not be blank")),
            null,
            null,
            null,
        )
        val problem = problems.problemOf(
            HandlerMethodValidationException(MethodValidationResult.create(Routes(), method, listOf(returned))),
        )
        problem.status.assert().isEqualTo(500)
        problem.code.assert().isNull()
    }

    @Test
    fun `a parameter compiled without its name is named by its element alone`() {
        val substring = String::class.java.getMethod("substring", Int::class.javaPrimitiveType)
        val unnamed = valueResult(
            MethodParameter(substring, 0),
            -1,
            listOf(resolvable("Min.arg0", "Min", "must be greater than or equal to 0")),
            null,
            null,
            null,
        )
        val problem = problems.problemOf(
            HandlerMethodValidationException(MethodValidationResult.create("", substring, listOf(unnamed))),
        )
        problem.violations.assert().containsExactly(Violation.of("", "min", "must be greater than or equal to 0"))
    }

    @Test
    fun `no mapper is offered a validation failure, even wrapped`() {
        val everything = ProblemMapper { WowProblemException("shop.claimed", 418, Retry.NONE, null, "Claimed.") }
        val mapped = FailureProblems(ErrorStatuses(cartManifest()), listOf(everything))
        val failure = WebExchangeBindException(parameter(0), bodyErrors())
        mapped.problemOf(failure).code.assert().isEqualTo(FrameworkCodes.REQUEST_INVALID)
        // Wrapped, only the application's own exception around it is offered.
        mapped.problemOf(IllegalStateException("Wrapped.", failure)).code.assert().isEqualTo("shop.claimed")
    }

    /** A parameter's or an element's result, built as Spring builds it: no container for a parameter itself. */
    private fun valueResult(
        parameter: MethodParameter,
        argument: Any?,
        errors: List<MessageSourceResolvable>,
        container: Any?,
        index: Int?,
        key: Any?,
    ): ParameterValidationResult = ParameterValidationResult::class.java.getConstructor(
        MethodParameter::class.java,
        Any::class.java,
        Collection::class.java,
        Any::class.java,
        Integer::class.java,
        Any::class.java,
        BiFunction::class.java,
    ).newInstance(parameter, argument, errors, container, index, key, BiFunction<Any, Any, Any?> { _, _ -> null })

    /** The body parameter's errors, of the element [index] of [container] when it has one. */
    private fun beanResult(container: Any?, index: Int?): ParameterErrors = ParameterErrors::class.java.getConstructor(
        MethodParameter::class.java,
        Any::class.java,
        Errors::class.java,
        Any::class.java,
        Integer::class.java,
        Any::class.java,
    ).newInstance(parameter(0), Item(" ", 0), bodyErrors(), container, index, null)

    private fun resolvable(specific: String, constraint: String, message: String): MessageSourceResolvable =
        DefaultMessageSourceResolvable(arrayOf(specific, constraint), message)
}
