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

package me.ahoo.wow.command.wait.stage

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.TEST_CONTEXT
import me.ahoo.wow.command.wait.TEST_FUNCTION
import me.ahoo.wow.command.wait.TEST_PROCESSOR
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitTransition
import me.ahoo.wow.command.wait.acceptedSignal
import me.ahoo.wow.command.wait.completed
import me.ahoo.wow.command.wait.finalSignal
import me.ahoo.wow.command.wait.testFunction
import me.ahoo.wow.command.wait.testSignal
import org.junit.jupiter.api.Test

class StageWaitStateTest {
    private val stateMachine = StageWaitStateDriver()

    @Test
    fun completeProcessedWithMergedResult() {
        val state = StageWaitState(CommandWait.processed("wait-id"))
        val sent = testSignal(CommandStage.SENT, result = mapOf("sent" to true), signalTime = 1)
        val processed = testSignal(CommandStage.PROCESSED, result = mapOf("processed" to true), signalTime = 2)

        val afterSent = stateMachine.next(state, sent)
        afterSent.completed.assert().isFalse()

        val afterProcessed = stateMachine.next(afterSent.state, processed)
        afterProcessed.completed.assert().isTrue()
        afterProcessed.finalSignal!!.stage.assert().isEqualTo(CommandStage.PROCESSED)
        afterProcessed.finalSignal.result["sent"].assert().isEqualTo(true)
        afterProcessed.finalSignal.result["processed"].assert().isEqualTo(true)
    }

    @Test
    fun assumesSignalsArePreRoutedByWaitCommandId() {
        val state = StageWaitState(CommandWait.processed("wait-id"))
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "another-wait-id",
            result = mapOf("processed" to true),
        )

        val reduction = stateMachine.next(state, processed)

        reduction.completed.assert().isTrue()
        reduction.acceptedSignal.assert().isEqualTo(processed)
        reduction.finalSignal!!.result["processed"].assert().isEqualTo(true)
    }

    @Test
    fun failFastWhenPreviousStageFails() {
        val state = StageWaitState(CommandWait.snapshot("wait-id"))
        val failedProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            errorCode = "FAILED",
            errorMsg = "processed failed",
        )

        val reduction = stateMachine.next(state, failedProcessed)

        reduction.completed.assert().isTrue()
        reduction.finalSignal!!.stage.assert().isEqualTo(CommandStage.PROCESSED)
        reduction.finalSignal.succeeded.assert().isFalse()
    }

    @Test
    fun completeWhenTargetStageFails() {
        val state = StageWaitState(CommandWait.processed("wait-id"))
        val failedProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            errorCode = "FAILED",
            errorMsg = "processed failed",
        )

        val reduction = stateMachine.next(state, failedProcessed)

        reduction.completed.assert().isTrue()
        reduction.acceptedSignal.assert().isEqualTo(failedProcessed)
        reduction.finalSignal!!.stage.assert().isEqualTo(CommandStage.PROCESSED)
        reduction.finalSignal.succeeded.assert().isFalse()
    }

    @Test
    fun waitForLastProjectionSignal() {
        val state = StageWaitState(
            CommandWait.projected("wait-id", TEST_CONTEXT, TEST_PROCESSOR, TEST_FUNCTION),
        )
        val processed = testSignal(CommandStage.PROCESSED)
        val firstProjection = testSignal(
            stage = CommandStage.PROJECTED,
            function = testFunction(),
            isLastProjection = false,
            result = mapOf("first" to true),
        )
        val lastProjection = testSignal(
            stage = CommandStage.PROJECTED,
            function = testFunction(),
            isLastProjection = true,
            result = mapOf("last" to true),
        )

        val afterProcessed = stateMachine.next(state, processed)
        val afterFirst = stateMachine.next(afterProcessed.state, firstProjection)
        val afterLast = stateMachine.next(afterFirst.state, lastProjection)

        afterFirst.completed.assert().isFalse()
        afterLast.completed.assert().isTrue()
        afterLast.finalSignal!!.result["last"].assert().isEqualTo(true)
    }

    @Test
    fun projectedSignalWaitsForProcessedBeforeCompleting() {
        val state = StageWaitState(
            CommandWait.projected("wait-id", TEST_CONTEXT, TEST_PROCESSOR, TEST_FUNCTION),
        )
        val lastProjection = testSignal(
            stage = CommandStage.PROJECTED,
            function = testFunction(),
            isLastProjection = true,
            result = mapOf("projected" to true),
        )
        val processed = testSignal(CommandStage.PROCESSED, result = mapOf("processed" to true))

        val afterProjection = stateMachine.next(state, lastProjection)
        val afterProcessed = stateMachine.next(afterProjection.state, processed)

        afterProjection.completed.assert().isFalse()
        afterProcessed.completed.assert().isTrue()
        afterProcessed.finalSignal!!.stage.assert().isEqualTo(CommandStage.PROJECTED)
        afterProcessed.finalSignal.result["projected"].assert().isEqualTo(true)
        afterProcessed.finalSignal.result["processed"].assert().isEqualTo(true)
    }
}

private class StageWaitStateDriver {
    fun next(state: StageWaitState, signal: WaitSignal): StageWaitStateTransition =
        StageWaitStateTransition(
            state = state,
            transition = state.next(signal),
        )
}

private data class StageWaitStateTransition(
    val state: StageWaitState,
    private val transition: WaitTransition,
) {
    val acceptedSignal: WaitSignal? = transition.acceptedSignal
    val completed: Boolean = transition.completed
    val finalSignal: WaitSignal? = transition.finalSignal
}
