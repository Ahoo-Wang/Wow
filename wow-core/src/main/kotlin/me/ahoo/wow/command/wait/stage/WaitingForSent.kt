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

/**
 * Wait strategy that completes immediately when the command is sent to the bus.
 * This is the most minimal wait strategy - it doesn't wait for any processing.
 * Supports void commands since no processing results are expected.
 *
 * @param waitCommandId The unique identifier for this wait strategy.
 */
class WaitingForSent(
    override val waitCommandId: String
) : WaitingForStage() {
    override val stage: CommandStage
        get() = CommandStage.SENT

    override val supportVoidCommand: Boolean = true
}
