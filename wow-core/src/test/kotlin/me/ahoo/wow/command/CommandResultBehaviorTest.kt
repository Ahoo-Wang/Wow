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
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.exception.ErrorCodes
import org.junit.jupiter.api.Test

class CommandResultBehaviorTest {

    @Test
    fun `should convert wait signal to command result`() {
        val command = AccountCommand(id = "account-1").toCommandMessage(
            id = "command-1",
            requestId = "request-1"
        )
        val bindingError = BindingError("field", "invalid")
        val signal = SimpleWaitSignal(
            id = "signal-1",
            waitCommandId = "wait-1",
            commandId = command.commandId,
            aggregateId = command.aggregateId,
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
            aggregateVersion = 3,
            errorCode = "COMMAND_ERROR",
            errorMsg = "command failed",
            bindingErrors = listOf(bindingError),
            result = mapOf("accepted" to false),
            signalTime = 2000
        )

        val result = signal.toResult(command)

        result.id.assert().isEqualTo("signal-1")
        result.waitCommandId.assert().isEqualTo("wait-1")
        result.stage.assert().isEqualTo(CommandStage.PROCESSED)
        result.contextName.assert().isEqualTo(COMMAND_FIXTURE_CONTEXT)
        result.aggregateName.assert().isEqualTo(COMMAND_FIXTURE_AGGREGATE)
        result.tenantId.assert().isEqualTo(command.aggregateId.tenantId)
        result.aggregateId.assert().isEqualTo("account-1")
        result.aggregateVersion.assert().isEqualTo(3)
        result.requestId.assert().isEqualTo("request-1")
        result.commandId.assert().isEqualTo("command-1")
        result.function.assert().isEqualTo(COMMAND_GATEWAY_FUNCTION)
        result.errorCode.assert().isEqualTo("COMMAND_ERROR")
        result.errorMsg.assert().isEqualTo("command failed")
        result.bindingErrors.assert().isEqualTo(listOf(bindingError))
        result.result.assert().isEqualTo(mapOf("accepted" to false))
        result.signalTime.assert().isEqualTo(2000)
        result.succeeded.assert().isFalse()
    }

    @Test
    fun `should convert throwable to failed command result`() {
        val command = AccountCommand(id = "account-1").toCommandMessage(
            id = "command-1",
            requestId = "request-1"
        )

        val result = IllegalStateException("cannot send").toResult(
            waitCommandId = "wait-1",
            commandMessage = command,
            id = "result-1",
            stage = CommandStage.SENT,
            result = mapOf("retry" to true),
            signalTime = 3000
        )

        result.id.assert().isEqualTo("result-1")
        result.waitCommandId.assert().isEqualTo("wait-1")
        result.stage.assert().isEqualTo(CommandStage.SENT)
        result.contextName.assert().isEqualTo(command.contextName)
        result.aggregateName.assert().isEqualTo(command.aggregateName)
        result.tenantId.assert().isEqualTo(command.aggregateId.tenantId)
        result.aggregateId.assert().isEqualTo(command.aggregateId.id)
        result.aggregateVersion.assert().isNull()
        result.requestId.assert().isEqualTo("request-1")
        result.commandId.assert().isEqualTo("command-1")
        result.function.assert().isEqualTo(COMMAND_GATEWAY_FUNCTION)
        result.errorCode.assert().isEqualTo(ErrorCodes.ILLEGAL_STATE)
        result.errorMsg.assert().isEqualTo("cannot send")
        result.bindingErrors.assert().isEmpty()
        result.result.assert().isEqualTo(mapOf("retry" to true))
        result.signalTime.assert().isEqualTo(3000)
        result.succeeded.assert().isFalse()
    }
}
