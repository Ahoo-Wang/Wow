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
import me.ahoo.wow.command.wait.WaitReductionState
import me.ahoo.wow.command.wait.testFunction
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.command.wait.testSignal
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
class WaitSignalReducerChainTest {
    private val reducer = ChainWaitSignalReducer()

    @Test
    fun completeWhenMainSagaHasNoTailCommandsAfterProcessedObserved() {
        val state = chainState()
        val saga = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = emptyList(),
        )
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
        )

        val afterSagaOnly = reducer.reduce(state, saga)
        val afterProcessed = reducer.reduce(afterSagaOnly.state, processed)

        afterSagaOnly.completed.assert().isFalse()
        afterProcessed.completed.assert().isTrue()
        afterProcessed.finalSignal!!.commandId.assert().isEqualTo("main-command")
    }

    @Test
    fun completeWhenAllTailCommandsComplete() {
        val state = chainState()
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
            commands = listOf("tail-1", "tail-2"),
            result = mapOf("main" to true),
        )
        val tail1 = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("tail1" to true),
        )
        val tail2 = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-2",
            result = mapOf("tail2" to true),
        )

        val afterMain = reducer.reduce(state, main)
        val afterTail1 = reducer.reduce(afterMain.state, tail1)
        val afterTail2 = reducer.reduce(afterTail1.state, tail2)
        val afterProcessed = reducer.reduce(afterTail2.state, processed)

        afterMain.completed.assert().isFalse()
        afterTail1.completed.assert().isFalse()
        afterTail2.completed.assert().isFalse()
        afterProcessed.completed.assert().isTrue()
        afterProcessed.finalSignal!!.result["main"].assert().isEqualTo(true)
        afterProcessed.finalSignal.result["tail1"].assert().isEqualTo(true)
        afterProcessed.finalSignal.result["tail2"].assert().isEqualTo(true)
    }

    @Test
    fun preMainTailSignalCompletesWhenMainConfirmsCommand() {
        val state = chainState()
        val tail = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("tail1" to true),
        )
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
            result = mapOf("main" to true),
        )

        val afterTail = reducer.reduce(state, tail)
        val afterProcessed = reducer.reduce(afterTail.state, processed)
        val afterMain = reducer.reduce(afterProcessed.state, main)

        afterTail.acceptedSignal.assert().isEqualTo(tail)
        afterTail.completed.assert().isFalse()
        afterProcessed.completed.assert().isFalse()
        afterMain.completed.assert().isTrue()
        afterMain.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        afterMain.finalSignal.result["main"].assert().isEqualTo(true)
        afterMain.finalSignal.result["tail1"].assert().isEqualTo(true)
    }

    @Test
    fun preMainFailedTailSignalCompletesFailedWhenMainConfirmsCommand() {
        val state = chainState()
        val failedTail = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            errorCode = "FAILED",
            errorMsg = "tail failed",
            result = mapOf("tail1" to true),
        )
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

        val afterTail = reducer.reduce(state, failedTail)
        val afterProcessed = reducer.reduce(afterTail.state, processed)
        val afterMain = reducer.reduce(afterProcessed.state, main)

        afterTail.acceptedSignal.assert().isEqualTo(failedTail)
        afterTail.completed.assert().isFalse()
        afterProcessed.completed.assert().isFalse()
        afterMain.completed.assert().isTrue()
        afterMain.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        afterMain.finalSignal.succeeded.assert().isFalse()
        afterMain.finalSignal.result["tail1"].assert().isEqualTo(true)
    }

    @Test
    fun preMainTailProcessedThenProjectedCompletesWhenMainConfirmsCommand() {
        val state = chainState(tailStage = CommandStage.PROJECTED)
        val tailProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("tailProcessed" to true),
        )
        val tailProjected = testSignal(
            stage = CommandStage.PROJECTED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            function = testFunction(),
            isLastProjection = true,
            result = mapOf("tailProjected" to true),
        )
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

        val afterTailProcessed = reducer.reduce(state, tailProcessed)
        val afterTailProjected = reducer.reduce(afterTailProcessed.state, tailProjected)
        val afterProcessed = reducer.reduce(afterTailProjected.state, processed)
        val afterMain = reducer.reduce(afterProcessed.state, main)

        afterTailProjected.completed.assert().isFalse()
        afterProcessed.completed.assert().isFalse()
        afterMain.completed.assert().isTrue()
        afterMain.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        afterMain.finalSignal.stage.assert().isEqualTo(CommandStage.PROJECTED)
        afterMain.finalSignal.result["tailProcessed"].assert().isEqualTo(true)
        afterMain.finalSignal.result["tailProjected"].assert().isEqualTo(true)
    }

    @Test
    fun preMainFailedTailProcessedCompletesFailedWhenMainConfirmsProjectedTail() {
        val state = chainState(tailStage = CommandStage.PROJECTED)
        val failedTailProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            errorCode = "FAILED",
            errorMsg = "tail processed failed",
        )
        val tailProjected = testSignal(
            stage = CommandStage.PROJECTED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            function = testFunction(),
            isLastProjection = true,
        )
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

        val afterTailProcessed = reducer.reduce(state, failedTailProcessed)
        val afterTailProjected = reducer.reduce(afterTailProcessed.state, tailProjected)
        val afterProcessed = reducer.reduce(afterTailProjected.state, processed)
        val afterMain = reducer.reduce(afterProcessed.state, main)

        afterMain.completed.assert().isTrue()
        afterMain.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        afterMain.finalSignal.stage.assert().isEqualTo(CommandStage.PROCESSED)
        afterMain.finalSignal.succeeded.assert().isFalse()
    }

    @Test
    fun preMainFailedTailProjectedCompletesFailedWhenMainConfirmsCommand() {
        val state = chainState(tailStage = CommandStage.PROJECTED)
        val tailProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
        )
        val failedTailProjected = testSignal(
            stage = CommandStage.PROJECTED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            function = testFunction(),
            isLastProjection = true,
            errorCode = "FAILED",
            errorMsg = "tail projected failed",
        )
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

        val afterTailProcessed = reducer.reduce(state, tailProcessed)
        val afterTailProjected = reducer.reduce(afterTailProcessed.state, failedTailProjected)
        val afterProcessed = reducer.reduce(afterTailProjected.state, processed)
        val afterMain = reducer.reduce(afterProcessed.state, main)

        afterMain.completed.assert().isTrue()
        afterMain.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        afterMain.finalSignal.stage.assert().isEqualTo(CommandStage.PROJECTED)
        afterMain.finalSignal.succeeded.assert().isFalse()
    }

    @Test
    fun preMainTailSignalsReplayInGlobalArrivalOrder() {
        val state = chainState()
        val tail2 = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-2",
            result = mapOf("winner" to "tail-2"),
        )
        val tail1 = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("winner" to "tail-1"),
        )
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
            commands = listOf("tail-1", "tail-2"),
        )

        val afterTail2 = reducer.reduce(state, tail2)
        val afterTail1 = reducer.reduce(afterTail2.state, tail1)
        val afterProcessed = reducer.reduce(afterTail1.state, processed)
        val afterMain = reducer.reduce(afterProcessed.state, main)

        afterMain.completed.assert().isTrue()
        afterMain.finalSignal!!.result["winner"].assert().isEqualTo("tail-1")
    }

    @Test
    fun preMainTailResultIsMergedBeforeLaterMainResult() {
        val state = chainState()
        val tail = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("winner" to "tail"),
        )
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
            result = mapOf("winner" to "processed"),
        )
        val main = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = listOf("tail-1"),
        )

        val afterTail = reducer.reduce(state, tail)
        val afterProcessed = reducer.reduce(afterTail.state, processed)
        val afterMain = reducer.reduce(afterProcessed.state, main)

        afterMain.completed.assert().isTrue()
        afterMain.finalSignal!!.result["winner"].assert().isEqualTo("processed")
    }

    @Test
    fun preMainFailedTailSignalsUseFirstFailureInGlobalArrivalOrder() {
        val state = chainState()
        val failedTail2 = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-2",
            errorCode = "FAILED_TAIL_2",
            errorMsg = "tail 2 failed",
        )
        val failedTail1 = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            errorCode = "FAILED_TAIL_1",
            errorMsg = "tail 1 failed",
        )
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
            commands = listOf("tail-1", "tail-2"),
        )

        val afterTail2 = reducer.reduce(state, failedTail2)
        val afterTail1 = reducer.reduce(afterTail2.state, failedTail1)
        val afterProcessed = reducer.reduce(afterTail1.state, processed)
        val afterMain = reducer.reduce(afterProcessed.state, main)

        afterMain.completed.assert().isTrue()
        afterMain.finalSignal!!.commandId.assert().isEqualTo("tail-2")
        afterMain.finalSignal.errorCode.assert().isEqualTo("FAILED_TAIL_2")
    }

    @Test
    fun delayedCompletionUsesFirstFailedTailByGlobalArrivalOrder() {
        val state = chainState()
        val failedTail2 = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-2",
            errorCode = "FAILED_TAIL_2",
            errorMsg = "tail 2 failed",
        )
        val failedTail1 = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            errorCode = "FAILED_TAIL_1",
            errorMsg = "tail 1 failed",
        )
        val main = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = listOf("tail-1", "tail-2"),
        )
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
        )

        val afterTail2 = reducer.reduce(state, failedTail2)
        val afterTail1 = reducer.reduce(afterTail2.state, failedTail1)
        val afterMain = reducer.reduce(afterTail1.state, main)
        val afterProcessed = reducer.reduce(afterMain.state, processed)

        afterMain.completed.assert().isFalse()
        afterProcessed.completed.assert().isTrue()
        afterProcessed.finalSignal!!.commandId.assert().isEqualTo("tail-2")
        afterProcessed.finalSignal.errorCode.assert().isEqualTo("FAILED_TAIL_2")
    }

    @Test
    fun updatingTailFinalSignalPreservesFirstFinalOrder() {
        val state = chainState(tailStage = CommandStage.PROJECTED)
        val main = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = listOf("tail-1", "tail-2"),
        )
        val tail1Projected = testSignal(
            stage = CommandStage.PROJECTED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            function = testFunction(),
            isLastProjection = true,
            errorCode = "FAILED_TAIL_1",
            errorMsg = "tail 1 failed",
        )
        val tail2Projected = testSignal(
            stage = CommandStage.PROJECTED,
            waitCommandId = "main-command",
            commandId = "tail-2",
            function = testFunction(),
            isLastProjection = true,
            errorCode = "FAILED_TAIL_2",
            errorMsg = "tail 2 failed",
        )
        val tail1Processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("tail1Processed" to true),
        )
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
        )

        val afterMain = reducer.reduce(state, main)
        val afterTail1Projected = reducer.reduce(afterMain.state, tail1Projected)
        val afterTail2Projected = reducer.reduce(afterTail1Projected.state, tail2Projected)
        val afterTail1Processed = reducer.reduce(afterTail2Projected.state, tail1Processed)
        val afterProcessed = reducer.reduce(afterTail1Processed.state, processed)

        afterProcessed.completed.assert().isTrue()
        afterProcessed.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        afterProcessed.finalSignal.errorCode.assert().isEqualTo("FAILED_TAIL_1")
        afterProcessed.finalSignal.result["tail1Processed"].assert().isEqualTo(true)
    }

    @Test
    fun tailFailureDoesNotBypassAfterProcessedGuard() {
        val state = chainState(tailStage = CommandStage.PROJECTED)
        val main = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = listOf("tail-1"),
        )
        val mainProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
        )
        val failedTailProjected = testSignal(
            stage = CommandStage.PROJECTED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            function = testFunction(),
            isLastProjection = true,
            errorCode = "FAILED_TAIL_PROJECTED",
            errorMsg = "tail projected failed",
        )
        val tailProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
        )

        val afterMain = reducer.reduce(state, main)
        val afterMainProcessed = reducer.reduce(afterMain.state, mainProcessed)
        val afterFailedTailProjected = reducer.reduce(afterMainProcessed.state, failedTailProjected)
        val afterTailProcessed = reducer.reduce(afterFailedTailProjected.state, tailProcessed)

        afterFailedTailProjected.completed.assert().isFalse()
        afterTailProcessed.completed.assert().isTrue()
        afterTailProcessed.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        afterTailProcessed.finalSignal.stage.assert().isEqualTo(CommandStage.PROJECTED)
        afterTailProcessed.finalSignal.succeeded.assert().isFalse()
        afterTailProcessed.finalSignal.errorCode.assert().isEqualTo("FAILED_TAIL_PROJECTED")
    }

    @Test
    fun tailFailureCompletesChainAfterMainProcessed() {
        val state = chainState()
        val main = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = listOf("tail-1", "tail-2"),
        )
        val failedTail = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            errorCode = "FAILED",
            errorMsg = "tail failed",
        )
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
        )

        val afterMain = reducer.reduce(state, main)
        val afterFailedTail = reducer.reduce(afterMain.state, failedTail)
        val afterProcessed = reducer.reduce(afterFailedTail.state, processed)

        afterFailedTail.completed.assert().isFalse()
        afterProcessed.completed.assert().isTrue()
        afterProcessed.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        afterProcessed.finalSignal.succeeded.assert().isFalse()
    }

    @Test
    fun duplicateMainSignalPreservesCompletedTailState() {
        val state = chainState()
        val main = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = listOf("tail-1", "tail-2"),
            result = mapOf("main" to true),
        )
        val tail1 = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("tail1" to true),
        )
        val tail2 = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-2",
            result = mapOf("tail2" to true),
        )
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
        )

        val afterMain = reducer.reduce(state, main)
        val afterTail1 = reducer.reduce(afterMain.state, tail1)
        val afterDuplicateMain = reducer.reduce(afterTail1.state, main)
        val afterTail2 = reducer.reduce(afterDuplicateMain.state, tail2)
        val afterProcessed = reducer.reduce(afterTail2.state, processed)

        afterDuplicateMain.state.tailStates["tail-1"].assert()
            .isEqualTo(afterTail1.state.tailStates["tail-1"])
        afterProcessed.completed.assert().isTrue()
        afterProcessed.finalSignal!!.result["tail1"].assert().isEqualTo(true)
        afterProcessed.finalSignal.result["tail2"].assert().isEqualTo(true)
    }

    @Test
    fun duplicateCompletedTailSignalIsIgnored() {
        val state = chainState()
        val main = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = listOf("tail-1"),
        )
        val tail = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("tail1" to true),
        )
        val duplicateTail = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            errorCode = "FAILED",
            errorMsg = "duplicate failed",
            result = mapOf("duplicate" to true),
        )
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
        )

        val afterMain = reducer.reduce(state, main)
        val afterTail = reducer.reduce(afterMain.state, tail)
        val afterDuplicateTail = reducer.reduce(afterTail.state, duplicateTail)
        val afterProcessed = reducer.reduce(afterDuplicateTail.state, processed)

        afterDuplicateTail.state.tailStates["tail-1"].assert()
            .isEqualTo(afterTail.state.tailStates["tail-1"])
        afterProcessed.completed.assert().isTrue()
        afterProcessed.finalSignal!!.succeeded.assert().isTrue()
        afterProcessed.finalSignal.result["tail1"].assert().isEqualTo(true)
        afterProcessed.finalSignal.result["duplicate"].assert().isNull()
    }

    @Test
    fun wrongMainFunctionIsIgnored() {
        val state = chainState()
        val wrongMain = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(name = "wrong-function"),
            commands = listOf("tail-1"),
        )

        val reduction = reducer.reduce(state, wrongMain)

        reduction.acceptedSignal.assert().isNull()
        reduction.state.assert().isEqualTo(state)
        reduction.completed.assert().isFalse()
    }

    @Test
    fun unconfirmedPendingTailSignalDoesNotAffectCompletion() {
        val state = chainState(tailStage = CommandStage.PROJECTED)
        val unconfirmedTailProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "unrelated-command",
            result = mapOf("unconfirmedProcessed" to true),
        )
        val unconfirmedTailProjected = testSignal(
            stage = CommandStage.PROJECTED,
            waitCommandId = "main-command",
            commandId = "unrelated-command",
            function = testFunction(),
            isLastProjection = true,
            result = mapOf("unconfirmedProjected" to true),
        )
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

        val afterUnconfirmedTailProcessed = reducer.reduce(state, unconfirmedTailProcessed)
        val afterUnconfirmedTailProjected = reducer.reduce(
            afterUnconfirmedTailProcessed.state,
            unconfirmedTailProjected,
        )
        val afterProcessed = reducer.reduce(afterUnconfirmedTailProjected.state, processed)
        val afterMain = reducer.reduce(afterProcessed.state, main)

        afterUnconfirmedTailProcessed.acceptedSignal.assert().isEqualTo(unconfirmedTailProcessed)
        afterUnconfirmedTailProjected.acceptedSignal.assert().isEqualTo(unconfirmedTailProjected)
        afterUnconfirmedTailProjected.completed.assert().isFalse()
        afterProcessed.completed.assert().isFalse()
        afterMain.completed.assert().isTrue()
        afterMain.finalSignal!!.commandId.assert().isEqualTo("main-command")
        afterMain.finalSignal.result["unconfirmedProcessed"].assert().isNull()
        afterMain.finalSignal.result["unconfirmedProjected"].assert().isNull()
    }

    @Test
    fun tailAfterProcessedStageRequiresTailProcessedSignal() {
        val state = chainState(tailStage = CommandStage.PROJECTED)
        val main = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = listOf("tail-1"),
        )
        val mainProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
        )
        val tailProjected = testSignal(
            stage = CommandStage.PROJECTED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            function = testFunction(),
            isLastProjection = true,
        )
        val tailProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
        )

        val afterMain = reducer.reduce(state, main)
        val afterMainProcessed = reducer.reduce(afterMain.state, mainProcessed)
        val afterTailProjected = reducer.reduce(afterMainProcessed.state, tailProjected)
        val afterTailProcessed = reducer.reduce(afterTailProjected.state, tailProcessed)

        afterTailProjected.completed.assert().isFalse()
        afterTailProcessed.completed.assert().isTrue()
        afterTailProcessed.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        afterTailProcessed.finalSignal.stage.assert().isEqualTo(CommandStage.PROJECTED)
    }

    private fun chainState(tailStage: CommandStage = CommandStage.PROCESSED): WaitReductionState =
        WaitReductionState.initial(
            CommandWait.chain("main-command", testNamedFunction(), tailStage, testNamedFunction()),
        )
}
