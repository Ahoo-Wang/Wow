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

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.command.CommandId
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.RequestId
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.NullableAggregateVersionCapable
import me.ahoo.wow.command.wait.SignalTimeCapable
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.id.generateGlobalId

data class CommandResult(
    val stage: CommandStage,
    val aggregateId: String,
    override val aggregateVersion: Int? = null,
    override val id: String,
    override val contextName: String,
    override val processorName: String,
    override val tenantId: String,
    override val requestId: String,
    override val commandId: String,
    override val errorCode: String = ErrorCodes.SUCCEEDED,
    override val errorMsg: String = ErrorCodes.SUCCEEDED_MESSAGE,
    override val bindingErrors: List<BindingError> = emptyList(),
    override val result: Map<String, Any> = emptyMap(),
    override val signalTime: Long = System.currentTimeMillis()
) : Identifier,
    CommandId,
    TenantId,
    RequestId,
    ErrorInfo,
    ProcessorInfo,
    CommandResultCapable,
    SignalTimeCapable,
    NullableAggregateVersionCapable

fun WaitSignal.toResult(commandMessage: CommandMessage<*>): CommandResult {
    return CommandResult(
        id = this.id,
        stage = this.stage,
        aggregateId = commandMessage.aggregateId.id,
        aggregateVersion = aggregateVersion,
        contextName = function.contextName,
        processorName = function.processorName,
        tenantId = commandMessage.aggregateId.tenantId,
        requestId = commandMessage.requestId,
        commandId = commandMessage.commandId,
        errorCode = this.errorCode,
        errorMsg = this.errorMsg,
        bindingErrors = bindingErrors,
        result = result,
        signalTime = signalTime
    )
}

fun Throwable.toResult(
    commandMessage: CommandMessage<*>,
    contextName: String = commandMessage.contextName,
    processorName: String,
    id: String = generateGlobalId(),
    stage: CommandStage = CommandStage.SENT,
    result: Map<String, Any> = emptyMap(),
    signalTime: Long = System.currentTimeMillis()
): CommandResult {
    val errorInfo = toErrorInfo()
    return CommandResult(
        id = id,
        stage = stage,
        aggregateId = commandMessage.aggregateId.id,
        contextName = contextName,
        processorName = processorName,
        tenantId = commandMessage.aggregateId.tenantId,
        requestId = commandMessage.requestId,
        commandId = commandMessage.commandId,
        errorCode = errorInfo.errorCode,
        errorMsg = errorInfo.errorMsg,
        bindingErrors = errorInfo.bindingErrors,
        result = result,
        signalTime = signalTime
    )
}
