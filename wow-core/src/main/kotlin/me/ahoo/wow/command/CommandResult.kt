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
import me.ahoo.wow.api.messaging.function.FunctionInfoCapable
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.modeling.AggregateNameCapable
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.naming.Materialized
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandStageCapable
import me.ahoo.wow.command.wait.NullableAggregateVersionCapable
import me.ahoo.wow.command.wait.SignalTimeCapable
import me.ahoo.wow.command.wait.WaitCommandIdCapable
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.id.generateGlobalId

/**
 * Represents the result of a command execution, containing all relevant information
 * about the command processing outcome.
 *
 * CommandResult encapsulates the state of command processing at a specific stage,
 * including success/failure status, aggregate version changes, and any additional
 * result data.
 *
 * @property id unique identifier for this result
 * @property waitCommandId the command ID being waited on
 * @property stage the current processing stage of the command
 * @property contextName the bounded context name
 * @property aggregateName the aggregate name
 * @property tenantId the tenant identifier
 * @property aggregateId the aggregate instance identifier
 * @property aggregateVersion the aggregate version after command processing:
 *                          - On success: the version after processing
 *                          - On gateway validation failure: null
 *                          - On processor execution failure: current aggregate version
 * @property requestId the request identifier
 * @property commandId the command identifier
 * @property function information about the function that processed the command
 * @property errorCode error code if processing failed
 * @property errorMsg error message if processing failed
 * @property bindingErrors list of binding validation errors
 * @property result additional result data as key-value pairs
 * @property signalTime timestamp when this result was generated
 * @see CommandStage
 * @see ErrorInfo
 * @see FunctionInfoData
 */
data class CommandResult(
    override val id: String,
    override val waitCommandId: String,
    override val stage: CommandStage,
    override val contextName: String,
    override val aggregateName: String,
    override val tenantId: String,
    val aggregateId: String,
    override val aggregateVersion: Int? = null,
    override val requestId: String,
    override val commandId: String,
    override val function: FunctionInfoData,
    override val errorCode: String = ErrorCodes.SUCCEEDED,
    override val errorMsg: String = ErrorCodes.SUCCEEDED_MESSAGE,
    override val bindingErrors: List<BindingError> = emptyList(),
    override val result: Map<String, Any> = emptyMap(),
    override val signalTime: Long = System.currentTimeMillis()
) : Identifier,
    WaitCommandIdCapable,
    CommandStageCapable,
    NamedBoundedContext,
    AggregateNameCapable,
    TenantId,
    NullableAggregateVersionCapable,
    CommandId,
    RequestId,
    ErrorInfo,
    FunctionInfoCapable<FunctionInfoData>,
    CommandResultCapable,
    SignalTimeCapable,
    Materialized

/**
 * Converts a WaitSignal to a CommandResult.
 *
 * This extension function transforms a wait signal into a command result,
 * mapping all the signal properties to the corresponding result fields.
 *
 * @param commandMessage the original command message
 * @return a CommandResult representing the wait signal
 * @see WaitSignal
 * @see CommandResult
 */
fun WaitSignal.toResult(commandMessage: CommandMessage<*>): CommandResult =
    CommandResult(
        id = this.id,
        waitCommandId = waitCommandId,
        stage = this.stage,
        contextName = aggregateId.contextName,
        aggregateName = aggregateId.aggregateName,
        tenantId = aggregateId.tenantId,
        aggregateId = aggregateId.id,
        aggregateVersion = aggregateVersion,
        function = function,
        requestId = commandMessage.requestId,
        commandId = commandId,
        errorCode = this.errorCode,
        errorMsg = this.errorMsg,
        bindingErrors = bindingErrors,
        result = result,
        signalTime = signalTime,
    )

/**
 * Converts a Throwable to a CommandResult representing a command failure.
 *
 * This extension function creates a CommandResult from an exception, extracting
 * error information and populating the result with failure details.
 *
 * @param waitCommandId the ID of the command being waited on
 * @param commandMessage the original command message that failed
 * @param function the function information (defaults to command gateway)
 * @param id unique identifier for the result (auto-generated if not provided)
 * @param stage the command stage when the error occurred (defaults to SENT)
 * @param result additional result data (defaults to empty map)
 * @param signalTime timestamp of the error (defaults to current time)
 * @return a CommandResult representing the failure
 * @see CommandResult
 * @see Throwable.toErrorInfo
 */
fun Throwable.toResult(
    waitCommandId: String,
    commandMessage: CommandMessage<*>,
    function: FunctionInfoData = COMMAND_GATEWAY_FUNCTION,
    id: String = generateGlobalId(),
    stage: CommandStage = CommandStage.SENT,
    result: Map<String, Any> = emptyMap(),
    signalTime: Long = System.currentTimeMillis()
): CommandResult {
    val errorInfo = toErrorInfo()
    return CommandResult(
        id = id,
        waitCommandId = waitCommandId,
        stage = stage,
        contextName = commandMessage.contextName,
        aggregateName = commandMessage.aggregateName,
        tenantId = commandMessage.aggregateId.tenantId,
        aggregateId = commandMessage.aggregateId.id,
        requestId = commandMessage.requestId,
        commandId = commandMessage.commandId,
        function = function,
        errorCode = errorInfo.errorCode,
        errorMsg = errorInfo.errorMsg,
        bindingErrors = errorInfo.bindingErrors,
        result = result,
        signalTime = signalTime,
    )
}
