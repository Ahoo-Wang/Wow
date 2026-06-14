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

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.chain.WaitingChainTail

interface WaitPlan : WaitCommandIdCapable {
    val target: WaitTarget
    val supportVoidCommand: Boolean

    fun propagate(commandWaitEndpoint: CommandWaitEndpoint, header: Header) {
        header
            .propagateWaitCommandId(waitCommandId)
            .propagateCommandWaitEndpoint(commandWaitEndpoint.endpoint)
            .propagateWaitTarget(target)
    }
}

sealed interface WaitTarget {
    val stage: CommandStage
    val function: NamedFunctionInfoData?

    fun shouldNotify(processingStage: CommandStage): Boolean =
        stage.shouldNotify(processingStage)

    fun shouldNotify(signal: WaitSignal): Boolean {
        if (stage.isPrevious(signal.stage)) {
            return true
        }
        if (stage != signal.stage) {
            return false
        }
        val waitingFunction = function ?: return true
        if (!stage.shouldWaitFunction) {
            return true
        }
        return waitingFunction.matchesWaitFunction(signal.function)
    }
}

data class StageWaitTarget(
    override val stage: CommandStage,
    override val function: NamedFunctionInfoData? = null,
) : WaitTarget

data class ChainWaitTarget(
    override val function: NamedFunctionInfoData,
    val tail: WaitingChainTail,
) : WaitTarget {
    override val stage: CommandStage
        get() = CommandStage.SAGA_HANDLED
}

data class SimpleWaitPlan(
    override val waitCommandId: String,
    override val target: WaitTarget,
    override val supportVoidCommand: Boolean = false,
) : WaitPlan
