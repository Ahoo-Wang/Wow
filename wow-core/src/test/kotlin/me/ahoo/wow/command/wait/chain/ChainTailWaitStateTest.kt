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

package me.ahoo.wow.command.wait.chain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitTransition
import me.ahoo.wow.command.wait.acceptedSignal
import me.ahoo.wow.command.wait.completed
import me.ahoo.wow.command.wait.finalSignal
import me.ahoo.wow.command.wait.testFunction
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.command.wait.testSignal
import org.junit.jupiter.api.Test

class ChainTailWaitStateTest {
    @Test
    fun storeAcceptedTailSignalBeforeMainChainMaterializesTailState() {
        val state = chainState()
        val tailSignal = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("tail" to true),
        )

        val transition = state.nextTransition(tailSignal)

        transition.acceptedSignal.assert().isEqualTo(tailSignal)
        transition.completed.assert().isFalse()
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
        )
        val main = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = listOf("tail-1"),
        )

        state.nextTransition(processed)
        val completed = state.nextTransition(main)

        completed.completed.assert().isTrue()
        completed.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        completed.finalSignal.result["tail"].assert().isEqualTo(true)
    }

    @Test
    fun ignoreUnrelatedWaitCommand() {
        val state = chainState()
        val tailSignal = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "other-command",
            commandId = "tail-1",
        )

        val transition = state.nextTransition(tailSignal)

        transition.acceptedSignal.assert().isNull()
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
        )
        val main = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = emptyList(),
        )

        state.nextTransition(processed)
        val completed = state.nextTransition(main)

        completed.completed.assert().isTrue()
        completed.finalSignal!!.commandId.assert().isEqualTo("main-command")
    }

    private fun chainState(): ChainWaitState =
        ChainWaitState(
            CommandWait.chain("main-command", testNamedFunction(), CommandStage.PROCESSED, testNamedFunction()),
        )
}

private fun ChainWaitState.nextTransition(signal: WaitSignal): ChainTailWaitStateTransition =
    ChainTailWaitStateTransition(next(signal))

private data class ChainTailWaitStateTransition(
    private val transition: WaitTransition,
) {
    val acceptedSignal: WaitSignal? = transition.acceptedSignal
    val completed: Boolean = transition.completed
    val finalSignal: WaitSignal? = transition.finalSignal
}
