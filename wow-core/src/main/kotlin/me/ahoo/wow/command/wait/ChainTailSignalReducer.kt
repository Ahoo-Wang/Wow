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

import me.ahoo.wow.command.wait.chain.WaitingChainTail

internal class ChainTailSignalReducer(
    private val stageReducer: StageWaitSignalReducer = StageWaitSignalReducer(),
    private val resultAccumulator: ChainResultAccumulator = ChainResultAccumulator(),
) {
    fun reducePendingTail(
        state: WaitReductionState,
        signal: WaitSignal,
        target: ChainWaitTarget,
    ): WaitReduction {
        if (signal.waitCommandId != state.plan.waitCommandId) {
            return WaitReduction(state = state)
        }
        if (!target.tail.toStageWaitTarget().shouldNotify(signal)) {
            return WaitReduction(state = state)
        }
        val pendingTailSignal = PendingTailSignal(
            sequence = state.nextSignalSequence,
            signal = signal,
        )
        return WaitReduction(
            state = state.copy(
                pendingTailSignals = state.pendingTailSignals + pendingTailSignal,
                nextSignalSequence = state.nextSignalSequence + 1,
            ),
            acceptedSignal = signal,
        )
    }

    fun reduceTail(
        state: WaitReductionState,
        signal: WaitSignal,
        tailState: WaitReductionState,
        signalSequence: Long? = null,
    ): ChainTailReduction {
        val tailReduction = stageReducer.reduce(tailState, signal)
        if (tailReduction.acceptedSignal == null) {
            return ChainTailReduction(state = state)
        }

        val stateWithResult = if (signalSequence == null) {
            resultAccumulator.activateNextSignalResult(state, signal)
        } else {
            resultAccumulator.activateSignalResult(state, signalSequence, signal)
        }
        val tailStates = state.tailStates + (signal.commandId to tailReduction.state)
        val tailFinalSignals = mergeTailFinalSignal(
            tailFinalSignals = state.tailFinalSignals,
            previousFinalSignal = tailState.finalSignal,
            currentFinalSignal = tailReduction.state.finalSignal,
        )
        return ChainTailReduction(
            state = stateWithResult.copy(
                tailStates = tailStates,
                tailFinalSignals = tailFinalSignals,
            ),
            acceptedSignal = signal,
            finalSignalCandidate = tailReduction.finalSignal,
        )
    }

    fun materializeTailStates(
        state: WaitReductionState,
        target: ChainWaitTarget,
        commandIds: List<String>,
    ): WaitReductionState {
        val tailStates = commandIds.fold(state.tailStates) { tailStates, commandId ->
            if (commandId in tailStates) {
                tailStates
            } else {
                tailStates + (commandId to initialTailState(target, commandId))
            }
        }
        return state.copy(tailStates = tailStates)
    }

    fun replayPendingTailSignals(state: WaitReductionState, commandIds: List<String>): WaitReductionState {
        var replayState = state
        val confirmedCommandIds = commandIds.toSet()
        val replaySignals = state.pendingTailSignals.filter { it.signal.commandId in confirmedCommandIds }
        if (replaySignals.isEmpty()) {
            return replayState
        }
        replayState = replayState.copy(
            pendingTailSignals = replayState.pendingTailSignals.filterNot {
                it.signal.commandId in confirmedCommandIds
            },
        )
        for ((sequence, pendingSignal) in replaySignals) {
            val currentTailState = replayState.tailStates[pendingSignal.commandId] ?: continue
            replayState = reduceTail(
                state = replayState,
                signal = pendingSignal,
                tailState = currentTailState,
                signalSequence = sequence,
            ).state
        }
        return replayState
    }

    fun isCompleted(state: WaitReductionState): Boolean =
        stageReducer.isCompleted(state)

    fun isCompletedFinalSignal(state: WaitReductionState, signal: WaitSignal): Boolean =
        state.tailStates[signal.commandId]?.let(stageReducer::isCompleted) == true

    private fun initialTailState(target: ChainWaitTarget, commandId: String): WaitReductionState =
        WaitReductionState.initial(
            CommandWait.stage(
                waitCommandId = commandId,
                stage = target.tail.stage,
                contextName = target.tail.function.contextName,
                processorName = target.tail.function.processorName,
                functionName = target.tail.function.name,
            ),
        )

    private fun WaitingChainTail.toStageWaitTarget(): StageWaitTarget =
        StageWaitTarget(stage = stage, function = function)

    private fun mergeTailFinalSignal(
        tailFinalSignals: List<WaitSignal>,
        previousFinalSignal: WaitSignal?,
        currentFinalSignal: WaitSignal?,
    ): List<WaitSignal> {
        if (currentFinalSignal == null) {
            return tailFinalSignals
        }
        val currentIndex = tailFinalSignals.indexOfFirst { it.commandId == currentFinalSignal.commandId }
        if (currentIndex == -1) {
            return tailFinalSignals + currentFinalSignal
        }
        if (currentFinalSignal == previousFinalSignal) {
            return tailFinalSignals
        }
        return tailFinalSignals.mapIndexed { index, tailFinalSignal ->
            if (index == currentIndex) {
                currentFinalSignal
            } else {
                tailFinalSignal
            }
        }
    }
}

internal data class ChainTailReduction(
    val state: WaitReductionState,
    val acceptedSignal: WaitSignal? = null,
    val finalSignalCandidate: WaitSignal? = null,
)
