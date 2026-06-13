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

internal class DefaultWaitSignalReducer : WaitSignalReducer {
    override fun reduce(state: WaitReductionState, signal: WaitSignal): WaitReduction {
        if (isCompleted(state)) {
            return WaitReduction(state = state, completed = true, finalSignal = state.finalSignal)
        }
        return when (state.plan.target) {
            is ChainWaitTarget -> reduceChain(state, signal)
            is StageWaitTarget -> reduceStage(state, signal)
        }
    }

    private fun reduceStage(state: WaitReductionState, signal: WaitSignal): WaitReduction {
        val target = state.plan.target
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

        if (isStageCompleted(nextState)) {
            return completed(
                state = nextState,
                acceptedSignal = signal,
                finalSignal = nextState.finalSignal!!,
            )
        }
        return WaitReduction(state = nextState, acceptedSignal = signal)
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

    private fun reduceChain(state: WaitReductionState, signal: WaitSignal): WaitReduction {
        val target = state.plan.target as ChainWaitTarget
        val tailState = state.tailStates[signal.commandId]
        if (tailState != null) {
            return reduceTail(state, signal, tailState)
        }
        if (signal.commandId != state.plan.waitCommandId) {
            return reducePendingTail(state, signal, target)
        }
        if (!target.shouldNotify(signal)) {
            return WaitReduction(state = state)
        }

        val processed = state.processed || signal.stage == CommandStage.PROCESSED
        val failedPrevious = !signal.succeeded && target.stage.isPrevious(signal.stage)
        if (failedPrevious) {
            val nextState = state.copy(processed = processed)
                .activateNextSignalResult(signal)
            val finalSignal = signal.copyResult(nextState.result)
            return completed(
                state = nextState.copy(
                    finalSignal = finalSignal,
                ),
                acceptedSignal = signal,
                finalSignal = finalSignal,
            )
        }
        if (!isMainChainSignal(target, signal)) {
            val nextState = state.copy(processed = processed)
                .activateNextSignalResult(signal)
            return completeChainIfReady(
                state = nextState,
                acceptedSignal = signal,
            )
        }

        val replayedState = state.copy(
            processed = processed,
        ).materializeTailStates(target, signal.commands)
            .replayPendingTailSignals(signal.commands)
        val acceptedState = replayedState.activateNextSignalResult(signal)
        val mainChainSignal = signal.copyResult(acceptedState.result)
        val nextState = acceptedState.copy(
            mainChainSignal = mainChainSignal,
        )

        return completeChainIfReady(
            state = nextState,
            acceptedSignal = signal,
        )
    }

    private fun reducePendingTail(
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

    private fun reduceTail(
        state: WaitReductionState,
        signal: WaitSignal,
        tailState: WaitReductionState,
        signalSequence: Long? = null,
    ): WaitReduction {
        val tailReduction = reduce(tailState, signal)
        if (tailReduction.acceptedSignal == null) {
            return WaitReduction(state = state)
        }

        val stateWithResult = if (signalSequence == null) {
            state.activateNextSignalResult(signal)
        } else {
            state.activateSignalResult(signalSequence, signal)
        }
        val tailStates = state.tailStates + (signal.commandId to tailReduction.state)
        val tailFinalSignals = mergeTailFinalSignal(
            tailFinalSignals = state.tailFinalSignals,
            previousFinalSignal = tailState.finalSignal,
            currentFinalSignal = tailReduction.state.finalSignal,
        )
        val nextState = stateWithResult.copy(
            tailStates = tailStates,
            tailFinalSignals = tailFinalSignals,
        )
        return completeChainIfReady(
            state = nextState,
            acceptedSignal = signal,
            finalSignalCandidate = tailReduction.finalSignal,
        )
    }

    private fun isMainChainSignal(target: ChainWaitTarget, signal: WaitSignal): Boolean =
        signal.stage == target.stage && target.function.matchesWaitFunction(signal.function)

    private fun WaitReductionState.materializeTailStates(
        target: ChainWaitTarget,
        commandIds: List<String>,
    ): WaitReductionState {
        val tailStates = commandIds.fold(tailStates) { tailStates, commandId ->
            if (commandId in tailStates) {
                tailStates
            } else {
                tailStates + (commandId to initialTailState(target, commandId))
            }
        }
        return copy(tailStates = tailStates)
    }

    private fun WaitReductionState.replayPendingTailSignals(commandIds: List<String>): WaitReductionState {
        var replayState = this
        val confirmedCommandIds = commandIds.toSet()
        val replaySignals = pendingTailSignals.filter { it.signal.commandId in confirmedCommandIds }
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
            if (replayState.finalSignal != null) {
                return replayState
            }
        }
        return replayState
    }

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

    private fun WaitReductionState.activateNextSignalResult(signal: WaitSignal): WaitReductionState {
        val sequence = nextSignalSequence
        return copy(nextSignalSequence = sequence + 1)
            .activateSignalResult(sequence, signal)
    }

    private fun WaitReductionState.activateSignalResult(sequence: Long, signal: WaitSignal): WaitReductionState {
        if (signal.result.isEmpty()) {
            return this
        }
        var nextResult: MutableMap<String, Any>? = null
        var nextResultSequences: MutableMap<String, Long>? = null
        for ((key, value) in signal.result) {
            val currentSequence = resultSequences[key]
            if (currentSequence == null || sequence >= currentSequence) {
                val resultMap = nextResult ?: result.toMutableMap().also {
                    nextResult = it
                }
                val resultSequenceMap = nextResultSequences ?: resultSequences.toMutableMap().also {
                    nextResultSequences = it
                }
                resultMap[key] = value
                resultSequenceMap[key] = sequence
            }
        }
        if (nextResult == null || nextResultSequences == null) {
            return this
        }
        return copy(
            result = nextResult.toMap(),
            resultSequences = nextResultSequences.toMap(),
        )
    }

    private fun isCompleted(state: WaitReductionState): Boolean =
        when (state.plan.target) {
            is ChainWaitTarget -> isChainCompleted(state)
            is StageWaitTarget -> isStageCompleted(state)
        }

    private fun isStageCompleted(state: WaitReductionState): Boolean {
        val finalSignal = state.finalSignal ?: return false
        val target = state.plan.target
        if (!finalSignal.succeeded && target.stage.isPrevious(finalSignal.stage)) {
            return true
        }
        return !target.stage.isAfterProcessed || state.processed
    }

    private fun isChainCompleted(state: WaitReductionState): Boolean {
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
        return state.tailStates.values.all(::isCompleted)
    }

    private fun completeChainIfReady(
        state: WaitReductionState,
        acceptedSignal: WaitSignal,
        finalSignalCandidate: WaitSignal? = null,
    ): WaitReduction {
        if (!isChainCompleted(state)) {
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
        tailStates[signal.commandId]?.let(::isCompleted) == true

    private fun completed(
        state: WaitReductionState,
        acceptedSignal: WaitSignal,
        finalSignal: WaitSignal,
    ): WaitReduction =
        WaitReduction(
            state = state,
            acceptedSignal = acceptedSignal,
            completed = true,
            finalSignal = finalSignal,
        )

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
