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

internal class ChainWaitSignalReducer(
    stageReducer: StageWaitSignalReducer = StageWaitSignalReducer(),
    private val resultAccumulator: ChainResultAccumulator = ChainResultAccumulator(),
    private val tailReducer: ChainTailSignalReducer = ChainTailSignalReducer(stageReducer, resultAccumulator),
) : WaitSignalReducer {
    override fun reduce(state: WaitReductionState, signal: WaitSignal): WaitReduction {
        if (isCompleted(state)) {
            return WaitReduction(state = state, completed = true, finalSignal = state.finalSignal)
        }
        return reduceActiveChain(state, signal)
    }

    private fun reduceActiveChain(state: WaitReductionState, signal: WaitSignal): WaitReduction {
        val target = state.plan.target as ChainWaitTarget
        val tailState = state.tailStates[signal.commandId]
        if (tailState != null) {
            return reduceMaterializedTail(state, signal, tailState)
        }
        if (signal.commandId != state.plan.waitCommandId) {
            return tailReducer.reducePendingTail(state, signal, target)
        }
        if (!target.shouldNotify(signal)) {
            return WaitReduction(state = state)
        }

        val processed = state.processed || signal.stage == CommandStage.PROCESSED
        val failedPrevious = !signal.succeeded && target.stage.isPrevious(signal.stage)
        if (failedPrevious) {
            return completeFailedPreviousSignal(state, signal, processed)
        }
        if (!isMainChainSignal(target, signal)) {
            return reduceMainProgressSignal(state, signal, processed)
        }

        return reduceConfirmedMainChainSignal(state, signal, target, processed)
    }

    private fun reduceMaterializedTail(
        state: WaitReductionState,
        signal: WaitSignal,
        tailState: WaitReductionState,
    ): WaitReduction {
        val tailReduction = tailReducer.reduceTail(state, signal, tailState)
        if (tailReduction.acceptedSignal == null) {
            return WaitReduction(state = state)
        }
        return completeChainIfReady(
            state = tailReduction.state,
            acceptedSignal = tailReduction.acceptedSignal,
            finalSignalCandidate = tailReduction.finalSignalCandidate,
        )
    }

    private fun completeFailedPreviousSignal(
        state: WaitReductionState,
        signal: WaitSignal,
        processed: Boolean,
    ): WaitReduction {
        val nextState = state.copy(processed = processed)
            .let {
                resultAccumulator.activateNextSignalResult(it, signal)
            }
        val finalSignal = signal.copyResult(nextState.result)
        return completed(
            state = nextState.copy(
                finalSignal = finalSignal,
            ),
            acceptedSignal = signal,
            finalSignal = finalSignal,
        )
    }

    private fun reduceMainProgressSignal(
        state: WaitReductionState,
        signal: WaitSignal,
        processed: Boolean,
    ): WaitReduction {
        val nextState = state.copy(processed = processed)
            .let {
                resultAccumulator.activateNextSignalResult(it, signal)
            }
        return completeChainIfReady(
            state = nextState,
            acceptedSignal = signal,
        )
    }

    private fun reduceConfirmedMainChainSignal(
        state: WaitReductionState,
        signal: WaitSignal,
        target: ChainWaitTarget,
        processed: Boolean,
    ): WaitReduction {
        val replayedState = tailReducer.replayPendingTailSignals(
            state = tailReducer.materializeTailStates(
                state = state.copy(processed = processed),
                target = target,
                commandIds = signal.commands,
            ),
            commandIds = signal.commands,
        )
        val acceptedState = resultAccumulator.activateNextSignalResult(replayedState, signal)
        val mainChainSignal = signal.copyResult(acceptedState.result)
        val nextState = acceptedState.copy(
            mainChainSignal = mainChainSignal,
        )

        return completeChainIfReady(
            state = nextState,
            acceptedSignal = signal,
        )
    }

    private fun isMainChainSignal(target: ChainWaitTarget, signal: WaitSignal): Boolean =
        signal.stage == target.stage && target.function.matchesWaitFunction(signal.function)

    private fun isCompleted(state: WaitReductionState): Boolean {
        if (state.finalSignal != null) {
            return true
        }
        val mainChainSignal = state.mainChainSignal ?: return false
        if (!state.processed) {
            return false
        }
        if (!mainChainSignal.succeeded) {
            return true
        }
        if (state.tailFinalSignals.any { !it.succeeded && state.isCompletedTailFinalSignal(it) }) {
            return true
        }
        return state.tailStates.values.all(tailReducer::isCompleted)
    }

    private fun completeChainIfReady(
        state: WaitReductionState,
        acceptedSignal: WaitSignal,
        finalSignalCandidate: WaitSignal? = null,
    ): WaitReduction {
        if (!isCompleted(state)) {
            return WaitReduction(state = state, acceptedSignal = acceptedSignal)
        }
        val finalSignal = selectChainFinalSignal(
            state = state,
            fallback = acceptedSignal,
            finalSignalCandidate = finalSignalCandidate,
        )
        return completed(
            state = state.copy(
                finalSignal = finalSignal,
                pendingTailSignals = emptyList(),
            ),
            acceptedSignal = acceptedSignal,
            finalSignal = finalSignal,
        )
    }

    private fun selectChainFinalSignal(
        state: WaitReductionState,
        fallback: WaitSignal,
        finalSignalCandidate: WaitSignal?,
    ): WaitSignal {
        val tailFinalSignals = state.tailFinalSignals
        val signal = tailFinalSignals.firstOrNull { !it.succeeded && state.isCompletedTailFinalSignal(it) }
            ?: state.mainChainSignal?.takeUnless { it.succeeded }
            ?: finalSignalCandidate
            ?: tailFinalSignals.lastOrNull { state.isCompletedTailFinalSignal(it) }
            ?: state.mainChainSignal
            ?: fallback
        return signal.copyResult(state.result)
    }

    private fun WaitReductionState.isCompletedTailFinalSignal(signal: WaitSignal): Boolean =
        tailReducer.isCompletedFinalSignal(this, signal)
}
