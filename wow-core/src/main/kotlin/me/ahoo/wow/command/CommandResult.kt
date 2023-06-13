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
import me.ahoo.wow.api.command.RequestId
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.asErrorInfo
import me.ahoo.wow.messaging.processor.ProcessorInfo

data class CommandResult(
    val stage: CommandStage,
    val aggregateId: String,
    override val contextName: String,
    override val processorName: String,
    override val tenantId: String,
    override val requestId: String,
    override val commandId: String,
    override val errorCode: String = ErrorCodes.SUCCEEDED,
    override val errorMsg: String = ErrorCodes.SUCCEEDED_MESSAGE
) : CommandId, TenantId, RequestId, ErrorInfo, ProcessorInfo

fun WaitSignal.asResult(commandMessage: CommandMessage<*>): CommandResult {
    return CommandResult(
        stage = this.stage,
        aggregateId = commandMessage.aggregateId.id,
        contextName = contextName,
        processorName = this.processorName,
        tenantId = commandMessage.aggregateId.tenantId,
        requestId = commandMessage.requestId,
        commandId = commandMessage.commandId,
        errorCode = this.errorCode,
        errorMsg = this.errorMsg,
    )
}

fun Throwable.asResult(
    commandMessage: CommandMessage<*>,
    contextName: String = commandMessage.contextName,
    processorName: String,
    stage: CommandStage = CommandStage.SENT
): CommandResult {
    val errorInfo = asErrorInfo()
    return CommandResult(
        stage = stage,
        aggregateId = commandMessage.aggregateId.id,
        contextName = contextName,
        processorName = processorName,
        tenantId = commandMessage.aggregateId.tenantId,
        requestId = commandMessage.requestId,
        commandId = commandMessage.commandId,
        errorCode = errorInfo.errorCode,
        errorMsg = errorInfo.errorMsg,
    )
}
