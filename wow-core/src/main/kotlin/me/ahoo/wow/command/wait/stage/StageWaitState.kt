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

import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.StageWaitTarget
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitState
import me.ahoo.wow.command.wait.WaitTarget
import me.ahoo.wow.command.wait.WaitTransition

internal class StageWaitState(
    override val plan: WaitPlan,
) : WaitState {
    private val target = plan.target as StageWaitTarget
    private val result = mutableMapOf<String, Any>()
    private var processed: Boolean = false
    override var completed: Boolean = false
        private set
    override var finalSignal: WaitSignal? = null
        private set

    override fun next(signal: WaitSignal): WaitTransition {
        if (completed) {
            return WaitTransition.Completed(finalSignal = finalSignal)
        }
        if (!target.shouldNotify(signal)) {
            return WaitTransition.Ignored
        }

        mergeResult(signal)
        processed = processed || signal.stage == CommandStage.PROCESSED
        if (!signal.succeeded && target.stage.isPrevious(signal.stage)) {
            return complete(signal, signal.copyResult(resultSnapshot()))
        }

        val selectedFinalSignal = selectStageFinalSignal(target, signal)
        if (selectedFinalSignal != null) {
            finalSignal = selectedFinalSignal
        } else if (signal.result.isNotEmpty()) {
            finalSignal = finalSignal?.copyResult(resultSnapshot())
        }

        if (canComplete()) {
            return complete(signal, finalSignal!!)
        }
        return WaitTransition.Accepted(signal)
    }

    private fun complete(acceptedSignal: WaitSignal, finalSignal: WaitSignal): WaitTransition {
        this.finalSignal = finalSignal
        completed = true
        return WaitTransition.Completed(
            acceptedSignal = acceptedSignal,
            finalSignal = finalSignal,
        )
    }

    private fun mergeResult(signal: WaitSignal) {
        if (signal.result.isNotEmpty()) {
            result.putAll(signal.result)
        }
    }

    private fun resultSnapshot(): Map<String, Any> =
        if (result.isEmpty()) {
            emptyMap()
        } else {
            result.toMap()
        }

    private fun canComplete(): Boolean {
        finalSignal ?: return false
        return !target.stage.isAfterProcessed || processed
    }

    private fun selectStageFinalSignal(
        target: WaitTarget,
        signal: WaitSignal,
    ): WaitSignal? {
        if (signal.stage != target.stage) {
            return null
        }
        if (target.stage == CommandStage.PROJECTED && !signal.isLastProjection) {
            return null
        }
        return signal.copyResult(resultSnapshot())
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
