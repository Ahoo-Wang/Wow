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

/**
 * Wait strategy that waits for sagas to handle command-generated events.
 * This strategy completes when both command processing and saga event handling are finished.
 * Sagas may generate additional commands as part of their processing.
 * Optionally filters by specific saga function criteria if provided.
 *
 * @param waitCommandId The unique identifier for this wait strategy.
 * @param function Optional function criteria to match specific saga handlers.
 */
class WaitingForSagaHandled(
    override val waitCommandId: String,
    override val function: NamedFunctionInfoData? = null
) : WaitingForFunction() {
    override val stage: CommandStage
        get() = CommandStage.SAGA_HANDLED
}
