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
import me.ahoo.wow.command.wait.ChainWaitTarget
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.WaitReductionState
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.command.wait.testSignal
import org.junit.jupiter.api.Test

class ChainTailSignalReducerTest {
    private val reducer = ChainTailSignalReducer()

    @Test
    fun reducePendingTailStoresAcceptedTailSignalBeforeMainChainMaterializesTailState() {
        val state = chainState()
        val target = state.plan.target as ChainWaitTarget
        val tailSignal = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("tail" to true),
        )

        val reduction = reducer.reducePendingTail(state, tailSignal, target)

        reduction.acceptedSignal.assert().isEqualTo(tailSignal)
        reduction.completed.assert().isFalse()
        reduction.state.pendingTailSignals.assert().hasSize(1)
        reduction.state.pendingTailSignals.first().sequence.assert().isEqualTo(0)
        reduction.state.pendingTailSignals.first().signal.assert().isEqualTo(tailSignal)
        reduction.state.nextSignalSequence.assert().isEqualTo(1)
        reduction.state.result.assert().isEmpty()
    }

    @Test
    fun reducePendingTailIgnoresUnrelatedWaitCommand() {
        val state = chainState()
        val target = state.plan.target as ChainWaitTarget
        val tailSignal = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "other-command",
            commandId = "tail-1",
        )

        val reduction = reducer.reducePendingTail(state, tailSignal, target)

        reduction.acceptedSignal.assert().isNull()
        reduction.state.assert().isEqualTo(state)
    }

    private fun chainState(): WaitReductionState =
        WaitReductionState.initial(
            CommandWait.chain("main-command", testNamedFunction(), CommandStage.PROCESSED, testNamedFunction()),
        )
}
