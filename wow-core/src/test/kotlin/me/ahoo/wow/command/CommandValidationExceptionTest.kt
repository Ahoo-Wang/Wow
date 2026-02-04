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

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.Path
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.command.CommandValidationException.Companion.toBindingErrors
import me.ahoo.wow.exception.ErrorCodes.COMMAND_VALIDATION
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test

class CommandValidationExceptionTest {
    @Test
    fun toBindingErrors() {
        val path = mockk<Path>()
        val constraintViolation = mockk<ConstraintViolation<MockCreateCommand>> {
            every { propertyPath } returns path
            every { message } returns "name is blank"
        }
        val bindingErrors = setOf(constraintViolation).toBindingErrors()
        bindingErrors.first().name.assert().isEqualTo(constraintViolation.propertyPath.toString())
        bindingErrors.first().msg.assert().isEqualTo(constraintViolation.message)
    }

    @Test
    fun test() {
        val command = MockCreateCommand(generateGlobalId())
        val path = mockk<Path>()
        every { path.toString() } returns "name"
        val constraintViolation = mockk<ConstraintViolation<MockCreateCommand>> {
            every { propertyPath } returns path
            every { message } returns "name is blank"
        }

        val exception =
            CommandValidationException(command, bindingErrors = listOf(BindingError("name", "name is blank")))
        exception.errorCode.assert().isEqualTo(COMMAND_VALIDATION)
        exception.message.assert().isEqualTo("name is blank")
        exception.errorMsg.assert().isEqualTo("name is blank")

        exception.bindingErrors.first().name.assert().isEqualTo(constraintViolation.propertyPath.toString())
        exception.bindingErrors.first().msg.assert().isEqualTo(constraintViolation.message)
    }

    @Test
    fun testIfEmpty() {
        val command = MockCreateCommand(generateGlobalId())
        val exception = CommandValidationException(command, "Command validation failed.")
        exception.errorCode.assert().isEqualTo(COMMAND_VALIDATION)
        exception.message.assert().isEqualTo("Command validation failed.")
        exception.errorMsg.assert().isEqualTo("Command validation failed.")
    }
}
