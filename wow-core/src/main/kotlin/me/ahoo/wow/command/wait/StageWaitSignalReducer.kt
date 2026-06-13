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

internal class StageWaitSignalReducer : WaitSignalReducer {
    override fun reduce(state: WaitReductionState, signal: WaitSignal): WaitReduction {
        if (isCompleted(state)) {
            return WaitReduction(state = state, completed = true, finalSignal = state.finalSignal)
        }
        val target = state.plan.target as StageWaitTarget
        if (!target.shouldNotify(signal)) {
            return WaitReduction(state = state)
        }

        val result = state.result + signal.result
        val processed = state.processed || signal.stage == CommandStage.PROCESSED
        val failedPrevious = !signal.succeeded && target.stage.isPrevious(signal.stage)
        if (failedPrevious) {
            val finalSignal = signal.copyResult(result)
            return completed(
                state = state.copy(
                    result = result,
                    finalSignal = finalSignal,
                    processed = processed,
                ),
                acceptedSignal = signal,
                finalSignal = finalSignal,
            )
        }

        val finalSignal = selectStageFinalSignal(target, signal, result)
            ?: state.finalSignal?.copyResult(result)
        val nextState = state.copy(
            result = result,
            finalSignal = finalSignal,
            processed = processed,
        )

        if (isCompleted(nextState)) {
            return completed(
                state = nextState,
                acceptedSignal = signal,
                finalSignal = nextState.finalSignal!!,
            )
        }
        return WaitReduction(state = nextState, acceptedSignal = signal)
    }

    fun isCompleted(state: WaitReductionState): Boolean {
        val finalSignal = state.finalSignal ?: return false
        val target = state.plan.target as StageWaitTarget
        if (!finalSignal.succeeded && target.stage.isPrevious(finalSignal.stage)) {
            return true
        }
        return !target.stage.isAfterProcessed || state.processed
    }

    private fun selectStageFinalSignal(
        target: WaitTarget,
        signal: WaitSignal,
        result: Map<String, Any>,
    ): WaitSignal? {
        if (signal.stage != target.stage) {
            return null
        }
        if (target.stage == CommandStage.PROJECTED && !signal.isLastProjection) {
            return null
        }
        return signal.copyResult(result)
    }

    private val CommandStage.isAfterProcessed: Boolean
        get() = when (this) {
            CommandStage.SNAPSHOT,
            CommandStage.PROJECTED,
            CommandStage.EVENT_HANDLED,
            CommandStage.SAGA_HANDLED,
            -> true
            CommandStage.SENT,
            CommandStage.PROCESSED,
            -> false
        }
}
