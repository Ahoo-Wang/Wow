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

import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitSignal

/**
 * Wait strategy that waits for projections to be updated based on command-generated events.
 * This strategy completes when both command processing and projection updates are finished.
 * Only completes on the final projection signal to ensure all projections are updated.
 * Optionally filters by specific projection function criteria if provided.
 *
 * @param waitCommandId The unique identifier for this wait strategy.
 * @param function Optional function criteria to match specific projection handlers.
 */
class WaitingForProjected(
    override val waitCommandId: String,
    override val function: NamedFunctionInfoData? = null
) : WaitingForFunction() {
    override val stage: CommandStage
        get() = CommandStage.PROJECTED

    override fun isWaitingFor(signal: WaitSignal): Boolean = super.isWaitingFor(signal) && signal.isLastProjection
}
