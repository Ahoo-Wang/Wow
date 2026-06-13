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
import org.junit.jupiter.api.Test

class WaitSignalReducerStageTest {
    private val reducer = DefaultWaitSignalReducer()

    @Test
    fun completeProcessedWithMergedResult() {
        val state = WaitReductionState.initial(CommandWait.processed("wait-id"))
        val sent = testSignal(CommandStage.SENT, result = mapOf("sent" to true), signalTime = 1)
        val processed = testSignal(CommandStage.PROCESSED, result = mapOf("processed" to true), signalTime = 2)

        val afterSent = reducer.reduce(state, sent)
        afterSent.completed.assert().isFalse()

        val afterProcessed = reducer.reduce(afterSent.state, processed)
        afterProcessed.completed.assert().isTrue()
        afterProcessed.finalSignal!!.stage.assert().isEqualTo(CommandStage.PROCESSED)
        afterProcessed.finalSignal.result["sent"].assert().isEqualTo(true)
        afterProcessed.finalSignal.result["processed"].assert().isEqualTo(true)
    }

    @Test
    fun assumesSignalsArePreRoutedByWaitCommandId() {
        val state = WaitReductionState.initial(CommandWait.processed("wait-id"))
        val processed = testSignal(
            stage = CommandStage.PROCESSED,
            waitCommandId = "another-wait-id",
            result = mapOf("processed" to true),
        )

        val reduction = reducer.reduce(state, processed)

        reduction.completed.assert().isTrue()
        reduction.acceptedSignal.assert().isEqualTo(processed)
        reduction.finalSignal!!.result["processed"].assert().isEqualTo(true)
    }

    @Test
    fun failFastWhenPreviousStageFails() {
        val state = WaitReductionState.initial(CommandWait.snapshot("wait-id"))
        val failedProcessed = testSignal(
            stage = CommandStage.PROCESSED,
            errorCode = "FAILED",
            errorMsg = "processed failed",
        )

        val reduction = reducer.reduce(state, failedProcessed)

        reduction.completed.assert().isTrue()
        reduction.finalSignal!!.stage.assert().isEqualTo(CommandStage.PROCESSED)
        reduction.finalSignal.succeeded.assert().isFalse()
    }

    @Test
    fun waitForLastProjectionSignal() {
        val state = WaitReductionState.initial(
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

        val afterProcessed = reducer.reduce(state, processed)
        val afterFirst = reducer.reduce(afterProcessed.state, firstProjection)
        val afterLast = reducer.reduce(afterFirst.state, lastProjection)

        afterFirst.completed.assert().isFalse()
        afterLast.completed.assert().isTrue()
        afterLast.finalSignal!!.result["last"].assert().isEqualTo(true)
    }
}
