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

import me.ahoo.wow.command.wait.ChainWaitTarget
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.StageWaitTarget
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitState
import me.ahoo.wow.command.wait.WaitTransition
import me.ahoo.wow.command.wait.acceptedSignal
import me.ahoo.wow.command.wait.finalSignal
import me.ahoo.wow.command.wait.matchesWaitFunction
import me.ahoo.wow.command.wait.stage.StageWaitState

internal class ChainWaitState(
    override val plan: WaitPlan,
) : WaitState {
    private val target = plan.target as ChainWaitTarget
    private val tailTarget = target.tail.toStageWaitTarget()
    private val result = mutableMapOf<String, Any>()
    private val resultSequences = mutableMapOf<String, Long>()
    private val tailStates = mutableMapOf<String, StageWaitState>()
    private val pendingTailSignals = mutableListOf<PendingTailSignal>()
    private val tailFinalSignals = mutableListOf<WaitSignal>()
    private var processed: Boolean = false
    private var mainChainSignal: WaitSignal? = null
    private var nextSignalSequence: Long = 0
    override var completed: Boolean = false
        private set
    override var finalSignal: WaitSignal? = null
        private set

    override fun next(signal: WaitSignal): WaitTransition {
        if (completed) {
            return WaitTransition.Completed(finalSignal = finalSignal)
        }
        return reduceActiveChain(signal)
    }

    private fun reduceActiveChain(signal: WaitSignal): WaitTransition {
        val tailState = tailStates[signal.commandId]
        if (tailState != null) {
            return reduceMaterializedTail(signal, tailState)
        }
        if (signal.commandId != plan.waitCommandId) {
            return reducePendingTail(signal)
        }
        if (!target.shouldNotify(signal)) {
            return WaitTransition.Ignored
        }

        processed = processed || signal.stage == CommandStage.PROCESSED
        if (!signal.succeeded && target.stage.isPrevious(signal.stage)) {
            return completeFailedPreviousSignal(signal)
        }
        if (!isMainChainSignal(signal)) {
            return reduceMainProgressSignal(signal)
        }

        return reduceConfirmedMainChainSignal(signal)
    }

    private fun reducePendingTail(signal: WaitSignal): WaitTransition {
        if (signal.waitCommandId != plan.waitCommandId) {
            return WaitTransition.Ignored
        }
        if (!tailTarget.shouldNotify(signal)) {
            return WaitTransition.Ignored
        }
        pendingTailSignals.add(
            PendingTailSignal(
                sequence = nextSignalSequence,
                signal = signal,
            ),
        )
        nextSignalSequence++
        return WaitTransition.Accepted(signal)
    }

    private fun reduceMaterializedTail(
        signal: WaitSignal,
        tailState: StageWaitState,
    ): WaitTransition {
        val tailTransition = reduceTail(signal, tailState) ?: return WaitTransition.Ignored
        return completeChainIfReady(
            acceptedSignal = tailTransition.acceptedSignal,
            finalSignalCandidate = tailTransition.finalSignalCandidate,
        )
    }

    private fun reduceTail(
        signal: WaitSignal,
        tailState: StageWaitState,
        signalSequence: Long? = null,
    ): TailTransition? {
        val previousFinalSignal = tailState.finalSignal
        val transition = tailState.next(signal)
        val acceptedSignal = transition.acceptedSignal ?: return null
        if (signalSequence == null) {
            activateNextSignalResult(signal)
        } else {
            activateSignalResult(signalSequence, signal)
        }
        mergeTailFinalSignal(
            previousFinalSignal = previousFinalSignal,
            currentFinalSignal = tailState.finalSignal,
        )
        return TailTransition(
            acceptedSignal = acceptedSignal,
            finalSignalCandidate = transition.finalSignal,
        )
    }

    private fun completeFailedPreviousSignal(signal: WaitSignal): WaitTransition {
        activateNextSignalResult(signal)
        val finalSignal = signal.copyResult(resultSnapshot())
        return complete(
            acceptedSignal = signal,
            finalSignal = finalSignal,
        )
    }

    private fun reduceMainProgressSignal(signal: WaitSignal): WaitTransition {
        activateNextSignalResult(signal)
        return completeChainIfReady(acceptedSignal = signal)
    }

    private fun reduceConfirmedMainChainSignal(signal: WaitSignal): WaitTransition {
        materializeTailStates(signal.commands)
        replayPendingTailSignals(signal.commands)
        activateNextSignalResult(signal)
        mainChainSignal = signal.copyResult(resultSnapshot())

        return completeChainIfReady(acceptedSignal = signal)
    }

    private fun materializeTailStates(commandIds: List<String>) {
        commandIds.forEach { commandId ->
            tailStates.computeIfAbsent(commandId) {
                initialTailState(commandId)
            }
        }
    }

    private fun replayPendingTailSignals(commandIds: List<String>) {
        if (commandIds.isEmpty() || pendingTailSignals.isEmpty()) {
            return
        }
        val confirmedCommandIds = commandIds.toSet()
        val replaySignals = mutableListOf<PendingTailSignal>()
        val iterator = pendingTailSignals.iterator()
        while (iterator.hasNext()) {
            val pendingTailSignal = iterator.next()
            if (pendingTailSignal.signal.commandId in confirmedCommandIds) {
                replaySignals.add(pendingTailSignal)
                iterator.remove()
            }
        }
        if (replaySignals.isEmpty()) {
            return
        }
        for ((sequence, pendingSignal) in replaySignals) {
            val currentTailState = tailStates.getValue(pendingSignal.commandId)
            reduceTail(
                signal = pendingSignal,
                tailState = currentTailState,
                signalSequence = sequence,
            )
        }
    }

    private fun activateNextSignalResult(signal: WaitSignal) {
        val sequence = nextSignalSequence
        nextSignalSequence++
        activateSignalResult(sequence, signal)
    }

    private fun activateSignalResult(sequence: Long, signal: WaitSignal) {
        if (signal.result.isEmpty()) {
            return
        }
        for ((key, value) in signal.result) {
            val currentSequence = resultSequences[key]
            if (currentSequence == null || sequence >= currentSequence) {
                result[key] = value
                resultSequences[key] = sequence
            }
        }
    }

    private fun completeChainIfReady(
        acceptedSignal: WaitSignal,
        finalSignalCandidate: WaitSignal? = null,
    ): WaitTransition {
        if (!canComplete()) {
            return WaitTransition.Accepted(acceptedSignal)
        }
        val finalSignal = selectChainFinalSignal(
            fallback = acceptedSignal,
            finalSignalCandidate = finalSignalCandidate,
        )
        return complete(
            acceptedSignal = acceptedSignal,
            finalSignal = finalSignal,
        ).also {
            pendingTailSignals.clear()
        }
    }

    private fun complete(
        acceptedSignal: WaitSignal,
        finalSignal: WaitSignal,
    ): WaitTransition {
        this.finalSignal = finalSignal
        completed = true
        return WaitTransition.Completed(
            acceptedSignal = acceptedSignal,
            finalSignal = finalSignal,
        )
    }

    private fun canComplete(): Boolean {
        val mainChainSignal = mainChainSignal ?: return false
        if (!processed) {
            return false
        }
        if (!mainChainSignal.succeeded) {
            return true
        }
        if (tailFinalSignals.any { !it.succeeded && isCompletedTailFinalSignal(it) }) {
            return true
        }
        return tailStates.values.all(StageWaitState::completed)
    }

    private fun selectChainFinalSignal(
        fallback: WaitSignal,
        finalSignalCandidate: WaitSignal?,
    ): WaitSignal {
        val signal = tailFinalSignals.firstOrNull { !it.succeeded && isCompletedTailFinalSignal(it) }
            ?: mainChainSignal?.takeUnless { it.succeeded }
            ?: finalSignalCandidate
            ?: tailFinalSignals.lastOrNull { isCompletedTailFinalSignal(it) }
            ?: mainChainSignal
            ?: fallback
        return signal.copyResult(resultSnapshot())
    }

    private fun mergeTailFinalSignal(
        previousFinalSignal: WaitSignal?,
        currentFinalSignal: WaitSignal?,
    ) {
        if (currentFinalSignal == null) {
            return
        }
        val currentIndex = tailFinalSignals.indexOfFirst { it.commandId == currentFinalSignal.commandId }
        if (currentIndex == -1) {
            tailFinalSignals.add(currentFinalSignal)
            return
        }
        if (currentFinalSignal == previousFinalSignal) {
            return
        }
        tailFinalSignals[currentIndex] = currentFinalSignal
    }

    private fun isMainChainSignal(signal: WaitSignal): Boolean =
        signal.stage == target.stage && target.function.matchesWaitFunction(signal.function)

    private fun isCompletedTailFinalSignal(signal: WaitSignal): Boolean =
        tailStates[signal.commandId]?.completed == true

    private fun initialTailState(commandId: String): StageWaitState =
        StageWaitState(
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

    private fun resultSnapshot(): Map<String, Any> =
        if (result.isEmpty()) {
            emptyMap()
        } else {
            result.toMap()
        }
}

internal data class PendingTailSignal(
    val sequence: Long,
    val signal: WaitSignal,
)

private data class TailTransition(
    val acceptedSignal: WaitSignal,
    val finalSignalCandidate: WaitSignal?,
)
