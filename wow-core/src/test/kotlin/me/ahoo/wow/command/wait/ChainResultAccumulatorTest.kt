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

class ChainResultAccumulatorTest {
    private val accumulator = ChainResultAccumulator()

    @Test
    fun activateNextSignalResultAdvancesSequenceEvenWithoutResult() {
        val state = WaitReductionState.initial(CommandWait.processed("wait-id"))
        val signal = testSignal(CommandStage.PROCESSED)

        val nextState = accumulator.activateNextSignalResult(state, signal)

        nextState.nextSignalSequence.assert().isEqualTo(1)
        nextState.result.assert().isEmpty()
        nextState.resultSequences.assert().isEmpty()
    }

    @Test
    fun activateSignalResultKeepsNewestValuePerKey() {
        val state = WaitReductionState.initial(CommandWait.processed("wait-id"))
            .copy(
                result = mapOf(
                    "shared" to "current",
                    "kept" to true,
                ),
                resultSequences = mapOf(
                    "shared" to 10,
                    "kept" to 1,
                ),
            )
        val olderSignal = testSignal(
            stage = CommandStage.PROCESSED,
            result = mapOf(
                "shared" to "older",
                "added" to "older",
            ),
        )
        val laterSignal = testSignal(
            stage = CommandStage.PROCESSED,
            result = mapOf("shared" to "later"),
        )

        val afterOlderSignal = accumulator.activateSignalResult(
            state = state,
            sequence = 9,
            signal = olderSignal,
        )
        val afterLaterSignal = accumulator.activateSignalResult(
            state = afterOlderSignal,
            sequence = 11,
            signal = laterSignal,
        )

        afterOlderSignal.result["shared"].assert().isEqualTo("current")
        afterOlderSignal.result["added"].assert().isEqualTo("older")
        afterOlderSignal.resultSequences["added"].assert().isEqualTo(9)
        afterLaterSignal.result["shared"].assert().isEqualTo("later")
        afterLaterSignal.resultSequences["shared"].assert().isEqualTo(11)
    }
}
