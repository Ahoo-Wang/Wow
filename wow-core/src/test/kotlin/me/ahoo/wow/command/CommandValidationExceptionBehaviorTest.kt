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
import me.ahoo.wow.command.CommandValidationException.Companion.toCommandValidationException
import me.ahoo.wow.exception.ErrorCodes
import org.junit.jupiter.api.Test

class CommandValidationExceptionBehaviorTest {

    @Test
    fun `should convert constraint violations to binding errors`() {
        val violation = mockk<ConstraintViolation<AccountCommand>> {
            every { propertyPath } returns FixedPath("name")
            every { message } returns "must not be blank"
        }

        val bindingErrors = setOf(violation).toBindingErrors()

        bindingErrors.assert().isEqualTo(listOf(BindingError("name", "must not be blank")))
    }

    @Test
    fun `should expose validation exception fields and fallback message from binding errors`() {
        val command = AccountCommand(id = "account-1")
        val exception = CommandValidationException(
            command = command,
            bindingErrors = listOf(BindingError("name", "must not be blank"))
        )

        exception.command.assert().isSameAs(command)
        exception.errorCode.assert().isEqualTo(ErrorCodes.COMMAND_VALIDATION)
        exception.errorMsg.assert().isEqualTo("must not be blank")
        exception.message.assert().isEqualTo("must not be blank")
        exception.bindingErrors.assert().isEqualTo(listOf(BindingError("name", "must not be blank")))
    }

    @Test
    fun `should use explicit validation error message`() {
        val command = AccountCommand(id = "account-1")

        val exception = CommandValidationException(
            command = command,
            errorMsg = "Command validation failed."
        )

        exception.errorCode.assert().isEqualTo(ErrorCodes.COMMAND_VALIDATION)
        exception.errorMsg.assert().isEqualTo("Command validation failed.")
        exception.message.assert().isEqualTo("Command validation failed.")
        exception.bindingErrors.assert().isEmpty()
    }

    @Test
    fun `should create validation exception from constraint violations`() {
        val command = AccountCommand(id = "account-1")
        val violation = mockk<ConstraintViolation<AccountCommand>> {
            every { propertyPath } returns FixedPath("version")
            every { message } returns "must be positive"
        }

        val exception = setOf(violation).toCommandValidationException(command, "invalid command")

        exception.command.assert().isSameAs(command)
        exception.errorMsg.assert().isEqualTo("invalid command")
        exception.bindingErrors.assert().isEqualTo(listOf(BindingError("version", "must be positive")))
    }
}

private class FixedPath(private val value: String) : Path {
    override fun iterator(): MutableIterator<Path.Node> = mutableListOf<Path.Node>().iterator()

    override fun toString(): String = value
}
