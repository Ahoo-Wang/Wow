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

package me.ahoo.wow.command.wait

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.wait.SimpleWaitSignal.Companion.toWaitSignal
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test

class WaitSignalDefaultsTest {

    @Test
    fun `a signal built from a function is a successful one of that function unless told otherwise`() {
        val function = FunctionInfoData(FunctionKind.EVENT, "context", "Processor", "onEvent")
        val aggregateId = "context.aggregate".toNamedAggregate().aggregateId("id-1")
        val before = System.currentTimeMillis()

        val signal = function.toWaitSignal(
            id = "signal-1",
            waitCommandId = "wait-1",
            commandId = "command-1",
            aggregateId = aggregateId,
            stage = CommandStage.PROJECTED,
        )

        signal.function.assert().isEqualTo(function)
        signal.stage.assert().isEqualTo(CommandStage.PROJECTED)
        signal.aggregateId.assert().isEqualTo(aggregateId)
        signal.succeeded.assert().isTrue()
        signal.errorCode.assert().isEqualTo(ErrorCodes.SUCCEEDED)
        signal.errorMsg.assert().isEqualTo(ErrorCodes.SUCCEEDED_MESSAGE)
        signal.isLastProjection.assert().isFalse()
        signal.aggregateVersion.assert().isNull()
        signal.bindingErrors.assert().isEmpty()
        signal.result.assert().isEmpty()
        signal.commands.assert().isEmpty()
        signal.signalTime.assert().isGreaterThanOrEqualTo(before)
        signal.copyResult(mapOf("k" to "v")).result.assert().isEqualTo(mapOf("k" to "v"))
    }
}
