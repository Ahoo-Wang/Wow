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

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.Path
import jakarta.validation.Validator
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.metadata.ConstraintDescriptor
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.kernel.CommandType
import org.junit.jupiter.api.Test

/** [JakartaCommandValidator] (decision D106). */
class JakartaCommandValidatorTest {
    private class Constrained(@field:NotBlank val sku: String, @field:Min(1) val quantity: Int)

    private val type = CommandType("shop.cart.AddItem", Constrained::class.java)

    private inline fun <reified A : Annotation> violation(path: String, field: String, message: String) =
        mockk<ConstraintViolation<Any>> {
            val annotation = Constrained::class.java.getDeclaredField(field).getAnnotation(A::class.java)
            every { propertyPath } returns mockk<Path> { every { this@mockk.toString() } returns path }
            val descriptor = mockk<ConstraintDescriptor<A>> { every { this@mockk.annotation } returns annotation }
            every { constraintDescriptor } returns descriptor
            every { this@mockk.message } returns message
        }

    @Test
    fun `a body without violations is valid`() {
        val validator = mockk<Validator> { every { validate(any<Any>()) } returns emptySet() }
        JakartaCommandValidator(validator).validate(type, Constrained("apple", 1)).assert().isEmpty()
    }

    @Test
    fun `violations are listed by path, then by constraint`() {
        val violations = setOf(
            violation<Min>("quantity", "quantity", "must be greater than or equal to 1"),
            violation<NotBlank>("sku", "sku", "must not be blank"),
            violation<NotBlank>("", "sku", "the body is invalid"),
            violation<Min>("sku", "quantity", "too short"),
        )
        val validator = mockk<Validator> { every { validate(any<Any>()) } returns violations }
        JakartaCommandValidator(validator).validate(type, Constrained(" ", 0)).assert().containsExactly(
            Violation("", "not-blank", "the body is invalid"),
            Violation("quantity", "min", "must be greater than or equal to 1"),
            Violation("sku", "min", "too short"),
            Violation("sku", "not-blank", "must not be blank"),
        )
    }
}
