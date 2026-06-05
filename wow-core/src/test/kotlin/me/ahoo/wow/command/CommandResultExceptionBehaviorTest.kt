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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.command.wait.CommandStage
import org.junit.jupiter.api.Test

class CommandResultExceptionBehaviorTest {

    @Test
    fun `should expose command result as error info`() {
        val bindingErrors = listOf(BindingError("field", "invalid"))
        val result = CommandResult(
            id = "result-1",
            waitCommandId = "wait-1",
            stage = CommandStage.PROCESSED,
            contextName = COMMAND_FIXTURE_CONTEXT,
            aggregateName = COMMAND_FIXTURE_AGGREGATE,
            tenantId = "tenant-1",
            aggregateId = "account-1",
            requestId = "request-1",
            commandId = "command-1",
            function = COMMAND_GATEWAY_FUNCTION,
            errorCode = "COMMAND_FAILED",
            errorMsg = "command failed",
            bindingErrors = bindingErrors
        )
        val cause = IllegalArgumentException("cause")

        val exception = CommandResultException(result, cause)

        exception.commandResult.assert().isSameAs(result)
        exception.errorInfo.assert().isSameAs(result)
        exception.errorCode.assert().isEqualTo("COMMAND_FAILED")
        exception.errorMsg.assert().isEqualTo("command failed")
        exception.message.assert().isEqualTo("command failed")
        exception.bindingErrors.assert().isSameAs(bindingErrors)
        exception.cause.assert().isSameAs(cause)
    }
}
