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

@Suppress("LargeClass")
class ChainWaitStateTest {
    private val stateMachine = ChainWaitStateDriver()

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

        val afterSagaOnly = stateMachine.next(state, saga)
        val afterProcessed = stateMachine.next(afterSagaOnly.state, processed)

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

        val afterMain = stateMachine.next(state, main)
        val afterTail1 = stateMachine.next(afterMain.state, tail1)
        val afterTail2 = stateMachine.next(afterTail1.state, tail2)
        val afterProcessed = stateMachine.next(afterTail2.state, processed)

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

        val afterTail = stateMachine.next(state, tail)
        val afterProcessed = stateMachine.next(afterTail.state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)

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

        val afterTail = stateMachine.next(state, failedTail)
        val afterProcessed = stateMachine.next(afterTail.state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)

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

        val afterTailProcessed = stateMachine.next(state, tailProcessed)
        val afterTailProjected = stateMachine.next(afterTailProcessed.state, tailProjected)
        val afterProcessed = stateMachine.next(afterTailProjected.state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)

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

        val afterTailProcessed = stateMachine.next(state, failedTailProcessed)
        val afterTailProjected = stateMachine.next(afterTailProcessed.state, tailProjected)
        val afterProcessed = stateMachine.next(afterTailProjected.state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)

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

        val afterTailProcessed = stateMachine.next(state, tailProcessed)
        val afterTailProjected = stateMachine.next(afterTailProcessed.state, failedTailProjected)
        val afterProcessed = stateMachine.next(afterTailProjected.state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)

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

        val afterTail2 = stateMachine.next(state, tail2)
        val afterTail1 = stateMachine.next(afterTail2.state, tail1)
        val afterProcessed = stateMachine.next(afterTail1.state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)

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

        val afterTail = stateMachine.next(state, tail)
        val afterProcessed = stateMachine.next(afterTail.state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)

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

        val afterTail2 = stateMachine.next(state, failedTail2)
        val afterTail1 = stateMachine.next(afterTail2.state, failedTail1)
        val afterProcessed = stateMachine.next(afterTail1.state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)

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

        val afterTail2 = stateMachine.next(state, failedTail2)
        val afterTail1 = stateMachine.next(afterTail2.state, failedTail1)
        val afterMain = stateMachine.next(afterTail1.state, main)
        val afterProcessed = stateMachine.next(afterMain.state, processed)

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

        val afterMain = stateMachine.next(state, main)
        val afterTail1Projected = stateMachine.next(afterMain.state, tail1Projected)
        val afterTail2Projected = stateMachine.next(afterTail1Projected.state, tail2Projected)
        val afterTail1Processed = stateMachine.next(afterTail2Projected.state, tail1Processed)
        val afterProcessed = stateMachine.next(afterTail1Processed.state, processed)

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

        val afterMain = stateMachine.next(state, main)
        val afterMainProcessed = stateMachine.next(afterMain.state, mainProcessed)
        val afterFailedTailProjected = stateMachine.next(afterMainProcessed.state, failedTailProjected)
        val afterTailProcessed = stateMachine.next(afterFailedTailProjected.state, tailProcessed)

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

        val afterMain = stateMachine.next(state, main)
        val afterFailedTail = stateMachine.next(afterMain.state, failedTail)
        val afterProcessed = stateMachine.next(afterFailedTail.state, processed)

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

        val afterMain = stateMachine.next(state, main)
        val afterTail1 = stateMachine.next(afterMain.state, tail1)
        val afterDuplicateMain = stateMachine.next(afterTail1.state, main)
        val afterTail2 = stateMachine.next(afterDuplicateMain.state, tail2)
        val afterProcessed = stateMachine.next(afterTail2.state, processed)

        afterDuplicateMain.acceptedSignal.assert().isEqualTo(main)
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

        val afterMain = stateMachine.next(state, main)
        val afterTail = stateMachine.next(afterMain.state, tail)
        val afterDuplicateTail = stateMachine.next(afterTail.state, duplicateTail)
        val afterProcessed = stateMachine.next(afterDuplicateTail.state, processed)

        afterDuplicateTail.acceptedSignal.assert().isNull()
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

        val reduction = stateMachine.next(state, wrongMain)

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

        val afterUnconfirmedTailProcessed = stateMachine.next(state, unconfirmedTailProcessed)
        val afterUnconfirmedTailProjected = stateMachine.next(
            afterUnconfirmedTailProcessed.state,
            unconfirmedTailProjected,
        )
        val afterProcessed = stateMachine.next(afterUnconfirmedTailProjected.state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)

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

        val afterMain = stateMachine.next(state, main)
        val afterMainProcessed = stateMachine.next(afterMain.state, mainProcessed)
        val afterTailProjected = stateMachine.next(afterMainProcessed.state, tailProjected)
        val afterTailProcessed = stateMachine.next(afterTailProjected.state, tailProcessed)

        afterTailProjected.completed.assert().isFalse()
        afterTailProcessed.completed.assert().isTrue()
        afterTailProcessed.finalSignal!!.commandId.assert().isEqualTo("tail-1")
        afterTailProcessed.finalSignal.stage.assert().isEqualTo(CommandStage.PROJECTED)
    }

    @Test
    fun completedChainReturnsStoredFinalSignalOnFurtherSignals() {
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
            commands = emptyList(),
        )
        val lateSignal = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
            result = mapOf("late" to true),
        )

        val afterProcessed = stateMachine.next(state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)
        val repeated = stateMachine.next(afterMain.state, lateSignal)

        afterMain.completed.assert().isTrue()
        repeated.completed.assert().isTrue()
        repeated.acceptedSignal.assert().isNull()
        repeated.finalSignal.assert().isEqualTo(afterMain.finalSignal)
        repeated.finalSignal!!.result["late"].assert().isNull()
    }

    @Test
    fun failedPreviousMainSignalCompletesChainImmediately() {
        val state = chainState()
        val failedSent = testSignal(
            stage = CommandStage.SENT,
            waitCommandId = "main-command",
            commandId = "main-command",
            errorCode = "FAILED_SENT",
            errorMsg = "sent failed",
            result = mapOf("sent" to false),
        )

        val transition = stateMachine.next(state, failedSent)

        transition.completed.assert().isTrue()
        transition.acceptedSignal.assert().isEqualTo(failedSent)
        transition.finalSignal!!.stage.assert().isEqualTo(CommandStage.SENT)
        transition.finalSignal.succeeded.assert().isFalse()
        transition.finalSignal.result["sent"].assert().isEqualTo(false)
    }

    @Test
    fun unrelatedPendingTailSignalsAreIgnored() {
        val state = chainState()
        val differentWaitCommand = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "other-wait-command",
            commandId = "tail-1",
        )
        val wrongTailStage = testSignal(
            stage = CommandStage.SNAPSHOT,
            waitCommandId = "main-command",
            commandId = "tail-1",
        )

        val afterDifferentWaitCommand = stateMachine.next(state, differentWaitCommand)
        val afterWrongTailStage = stateMachine.next(afterDifferentWaitCommand.state, wrongTailStage)

        afterDifferentWaitCommand.acceptedSignal.assert().isNull()
        afterWrongTailStage.acceptedSignal.assert().isNull()
        afterWrongTailStage.completed.assert().isFalse()
    }

    @Test
    fun unconfirmedPendingTailSignalIsIgnoredWhenMainConfirmsDifferentTail() {
        val state = chainState()
        val unconfirmedTail = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "unconfirmed-tail",
            result = mapOf("unconfirmed" to true),
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
        val confirmedTail = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "tail-1",
            result = mapOf("tail1" to true),
        )

        val afterUnconfirmedTail = stateMachine.next(state, unconfirmedTail)
        val afterProcessed = stateMachine.next(afterUnconfirmedTail.state, processed)
        val afterMain = stateMachine.next(afterProcessed.state, main)
        val afterConfirmedTail = stateMachine.next(afterMain.state, confirmedTail)

        afterUnconfirmedTail.acceptedSignal.assert().isEqualTo(unconfirmedTail)
        afterMain.completed.assert().isFalse()
        afterConfirmedTail.completed.assert().isTrue()
        afterConfirmedTail.finalSignal!!.result["unconfirmed"].assert().isNull()
        afterConfirmedTail.finalSignal.result["tail1"].assert().isEqualTo(true)
    }

    @Test
    fun failedMainChainSignalCompletesAfterProcessedWithoutWaitingForTails() {
        val state = chainState()
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "main-command",
            commandId = "main-command",
            result = mapOf("processed" to true),
        )
        val failedMain = testSignal(
            stage = CommandStage.SAGA_HANDLED,
            waitCommandId = "main-command",
            commandId = "main-command",
            function = testFunction(),
            commands = listOf("tail-1"),
            errorCode = "FAILED_MAIN",
            errorMsg = "main failed",
            result = mapOf("main" to false),
        )

        val afterProcessed = stateMachine.next(state, processed)
        val afterFailedMain = stateMachine.next(afterProcessed.state, failedMain)

        afterFailedMain.completed.assert().isTrue()
        afterFailedMain.acceptedSignal.assert().isEqualTo(failedMain)
        afterFailedMain.finalSignal!!.commandId.assert().isEqualTo("main-command")
        afterFailedMain.finalSignal.succeeded.assert().isFalse()
        afterFailedMain.finalSignal.errorCode.assert().isEqualTo("FAILED_MAIN")
        afterFailedMain.finalSignal.result["processed"].assert().isEqualTo(true)
        afterFailedMain.finalSignal.result["main"].assert().isEqualTo(false)
    }

    private fun chainState(tailStage: CommandStage = CommandStage.PROCESSED): ChainWaitState =
        ChainWaitState(
            CommandWait.chain("main-command", testNamedFunction(), tailStage, testNamedFunction()),
        )
}

private class ChainWaitStateDriver {
    fun next(state: ChainWaitState, signal: WaitSignal): ChainWaitStateTransition =
        ChainWaitStateTransition(
            state = state,
            transition = state.next(signal),
        )
}

private data class ChainWaitStateTransition(
    val state: ChainWaitState,
    private val transition: WaitTransition,
) {
    val acceptedSignal: WaitSignal? = transition.acceptedSignal
    val completed: Boolean = transition.completed
    val finalSignal: WaitSignal? = transition.finalSignal
}
