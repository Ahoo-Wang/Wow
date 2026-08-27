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

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.Path
import jakarta.validation.Validator
import jakarta.validation.executable.ExecutableValidator
import jakarta.validation.metadata.BeanDescriptor
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.command.CommandValidationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ValidatorsTest {

    @Test
    fun `should return command when validator has no violations`() {
        val command = ValidatedCommand("account-1")

        val returned = NoOpValidator.validateCommand(command)

        returned.assert().isSameAs(command)
    }

    @Test
    fun `should throw command validation exception when validator has violations`() {
        val command = ValidatedCommand("")
        val violation = constraintViolation<ValidatedCommand>("id", "must not be blank")
        val validator = FixedViolationValidator(setOf(violation))

        val exception = assertThrows<CommandValidationException> {
            validator.validateCommand(command)
        }

        exception.command.assert().isSameAs(command)
        exception.bindingErrors.assert().isEqualTo(listOf(BindingError("id", "must not be blank")))
        exception.errorMsg.assert().isEqualTo("must not be blank")
    }
}

private data class ValidatedCommand(val id: String)

private fun <T : Any> constraintViolation(
    property: String,
    violationMessage: String
): ConstraintViolation<T> {
    return mockk<ConstraintViolation<T>> {
        every { propertyPath } returns FixedPath(property)
        every { message } returns violationMessage
    }
}

private class FixedPath(private val value: String) : Path {
    override fun iterator(): MutableIterator<Path.Node> = mutableListOf<Path.Node>().iterator()

    override fun toString(): String = value
}

private class FixedViolationValidator(
    private val violations: Set<ConstraintViolation<ValidatedCommand>>
) : Validator {
    override fun <T : Any> validate(
        `object`: T,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> {
        @Suppress("UNCHECKED_CAST")
        return violations as Set<ConstraintViolation<T>>
    }

    override fun <T : Any> validateProperty(
        `object`: T,
        propertyName: String,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> = emptySet()

    override fun <T : Any> validateValue(
        beanType: Class<T>,
        propertyName: String,
        value: Any,
        vararg groups: Class<*>
    ): Set<ConstraintViolation<T>> = emptySet()

    override fun getConstraintsForClass(clazz: Class<*>): BeanDescriptor = throw UnsupportedOperationException()

    override fun <T : Any> unwrap(type: Class<T>): T = throw UnsupportedOperationException()

    override fun forExecutables(): ExecutableValidator = throw UnsupportedOperationException()
}
