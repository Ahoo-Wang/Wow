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

internal class ChainResultAccumulator {
    fun activateNextSignalResult(state: WaitReductionState, signal: WaitSignal): WaitReductionState {
        val sequence = state.nextSignalSequence
        return activateSignalResult(
            state = state.copy(nextSignalSequence = sequence + 1),
            sequence = sequence,
            signal = signal,
        )
    }

    fun activateSignalResult(
        state: WaitReductionState,
        sequence: Long,
        signal: WaitSignal,
    ): WaitReductionState {
        if (signal.result.isEmpty()) {
            return state
        }
        var nextResult: MutableMap<String, Any>? = null
        var nextResultSequences: MutableMap<String, Long>? = null
        for ((key, value) in signal.result) {
            val currentSequence = state.resultSequences[key]
            if (currentSequence == null || sequence >= currentSequence) {
                val resultMap = nextResult ?: state.result.toMutableMap().also {
                    nextResult = it
                }
                val resultSequenceMap = nextResultSequences ?: state.resultSequences.toMutableMap().also {
                    nextResultSequences = it
                }
                resultMap[key] = value
                resultSequenceMap[key] = sequence
            }
        }
        if (nextResult == null || nextResultSequences == null) {
            return state
        }
        return state.copy(
            result = nextResult.toMap(),
            resultSequences = nextResultSequences.toMap(),
        )
    }
}
