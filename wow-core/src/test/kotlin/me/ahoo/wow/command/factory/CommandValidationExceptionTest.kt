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

package me.ahoo.wow.command.factory

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.Path
import me.ahoo.wow.command.MockCreateCommand
import me.ahoo.wow.exception.ErrorCodes.COMMAND_VALIDATION
import me.ahoo.wow.id.GlobalIdGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CommandValidationExceptionTest {
    @Test
    fun test() {
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString())
        val path = mockk<Path>()
        every { path.toString() } returns "name"
        val constraintViolation = mockk<ConstraintViolation<MockCreateCommand>> {
            every { propertyPath } returns path
            every { message } returns "name is blank"
        }
        val exception = CommandValidationException(command, setOf(constraintViolation))
        assertThat(exception.errorCode, equalTo(COMMAND_VALIDATION))
        assertThat(exception.message, equalTo("name:name is blank"))
        assertThat(exception.errorMsg, equalTo("name:name is blank"))
        assertThat(
            exception.bindingErrors.first().name,
            equalTo(constraintViolation.propertyPath.toString())
        )
        assertThat(
            exception.bindingErrors.first().msg,
            equalTo(constraintViolation.message)
        )
    }

    @Test
    fun testIfEmpty() {
        val command = MockCreateCommand(GlobalIdGenerator.generateAsString())
        val exception = CommandValidationException(command, setOf())
        assertThat(exception.errorCode, equalTo(COMMAND_VALIDATION))
        assertThat(exception.message, equalTo("Command validation failed."))
        assertThat(exception.errorMsg, equalTo("Command validation failed."))
    }
}
