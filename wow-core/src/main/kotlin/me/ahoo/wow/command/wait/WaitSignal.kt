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

import me.ahoo.wow.api.command.CommandId
import me.ahoo.wow.api.exception.ErrorCodes
import me.ahoo.wow.api.exception.ErrorInfo

interface WaitSignal : CommandId, ErrorInfo {
    val stage: CommandStage
    val isLastProjection: Boolean
        get() = false
}

data class SimpleWaitSignal(
    override val commandId: String,
    override val stage: CommandStage,
    override val isLastProjection: Boolean = false,
    override val errorCode: String = ErrorCodes.SUCCEEDED,
    override val errorMsg: String = ErrorCodes.SUCCEEDED_MSG,
) : WaitSignal
