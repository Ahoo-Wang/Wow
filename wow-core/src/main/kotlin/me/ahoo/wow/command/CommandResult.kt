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

package me.ahoo.wow.command

import me.ahoo.wow.api.command.CommandId
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.RequestId
import me.ahoo.wow.api.exception.ErrorCodes
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitSignal

data class CommandResult(
    val stage: CommandStage,
    val aggregateId: String,
    override val tenantId: String,
    override val requestId: String,
    override val commandId: String,
    override val errorCode: String = ErrorCodes.SUCCEEDED,
    override val errorMsg: String = ErrorCodes.SUCCEEDED_MSG
) : CommandId, TenantId, RequestId, ErrorInfo

fun WaitSignal.asResult(commandMessage: CommandMessage<*>): CommandResult {
    return CommandResult(
        stage = this.stage,
        aggregateId = commandMessage.aggregateId.id,
        tenantId = commandMessage.aggregateId.tenantId,
        requestId = commandMessage.requestId,
        commandId = commandMessage.commandId,
        errorCode = this.errorCode,
        errorMsg = this.errorMsg,
    )
}
