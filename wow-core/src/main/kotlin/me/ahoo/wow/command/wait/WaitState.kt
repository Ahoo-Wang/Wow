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

import me.ahoo.wow.command.wait.chain.ChainWaitState
import me.ahoo.wow.command.wait.stage.StageWaitState

internal interface WaitState {
    val plan: WaitPlan
    val completed: Boolean
    val finalSignal: WaitSignal?

    fun next(signal: WaitSignal): WaitTransition
}

internal sealed interface WaitTransition {
    data object Ignored : WaitTransition

    data class Accepted(
        val signal: WaitSignal,
    ) : WaitTransition

    data class Completed(
        val acceptedSignal: WaitSignal? = null,
        val finalSignal: WaitSignal? = null,
    ) : WaitTransition
}

internal val WaitTransition.acceptedSignal: WaitSignal?
    get() = when (this) {
        is WaitTransition.Accepted -> signal
        is WaitTransition.Completed -> acceptedSignal
        WaitTransition.Ignored -> null
    }

internal val WaitTransition.completed: Boolean
    get() = this is WaitTransition.Completed

internal val WaitTransition.finalSignal: WaitSignal?
    get() = when (this) {
        is WaitTransition.Completed -> finalSignal
        else -> null
    }

internal fun createWaitState(plan: WaitPlan): WaitState =
    when (plan.target) {
        is StageWaitTarget -> StageWaitState(plan)
        is ChainWaitTarget -> ChainWaitState(plan)
    }
