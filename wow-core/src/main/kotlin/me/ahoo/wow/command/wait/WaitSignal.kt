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

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.command.CommandId
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.FunctionInfoCapable
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.materialize
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.command.CommandResultCapable
import me.ahoo.wow.exception.ErrorCodes

/**
 * Interface for objects that have a signal timestamp.
 */
interface SignalTimeCapable {
    val signalTime: Long
}

/**
 * Interface for objects that may have an aggregate version.
 */
interface NullableAggregateVersionCapable {
    val aggregateVersion: Int?
}

/**
 * Signal representing a command processing stage completion.
 *
 * WaitSignal contains all information about a specific stage in command processing,
 * including success/failure status, aggregate state, and any generated commands.
 *
 * @property stage the command processing stage this signal represents
 * @property isLastProjection whether this is the final projection for the command
 * @property commands list of command IDs sent by Saga as a result of this processing
 * @see CommandStage
 * @see SimpleWaitSignal
 */
interface WaitSignal :
    Identifier,
    WaitCommandIdCapable,
    CommandId,
    AggregateIdCapable,
    NullableAggregateVersionCapable,
    ErrorInfo,
    SignalTimeCapable,
    CommandResultCapable,
    FunctionInfoCapable<FunctionInfoData> {
    val stage: CommandStage
    val isLastProjection: Boolean

    /**
     * List of command IDs sent by Saga
     */
    val commands: List<String>

    /**
     * Creates a copy of this signal with updated result data.
     *
     * @param result the new result data to include
     * @return a new WaitSignal with the updated result
     */
    fun copyResult(result: Map<String, Any>): WaitSignal
}

/**
 * Simple implementation of WaitSignal.
 *
 * This data class provides a concrete implementation of the WaitSignal interface
 * with all necessary properties for tracking command processing stages.
 *
 * @param id unique identifier for this signal
 * @param waitCommandId the command ID being waited on
 * @param commandId the command that generated this signal
 * @param aggregateId the aggregate this signal relates to
 * @param stage the processing stage this signal represents
 * @param function information about the function that generated this signal
 * @param aggregateVersion the aggregate version at signal time (optional)
 * @param isLastProjection whether this is the final projection
 * @param errorCode error code if processing failed
 * @param errorMsg error message if processing failed
 * @param bindingErrors validation errors if any
 * @param result additional result data
 * @param commands command IDs sent by Saga
 * @param signalTime timestamp when this signal was generated
 * @see WaitSignal
 */
data class SimpleWaitSignal(
    override val id: String,
    override val waitCommandId: String,
    override val commandId: String,
    override val aggregateId: AggregateId,
    override val stage: CommandStage,
    override val function: FunctionInfoData,
    override val aggregateVersion: Int? = null,
    override val isLastProjection: Boolean = false,
    override val errorCode: String = ErrorCodes.SUCCEEDED,
    override val errorMsg: String = ErrorCodes.SUCCEEDED_MESSAGE,
    override val bindingErrors: List<BindingError> = emptyList(),
    override val result: Map<String, Any> = emptyMap(),
    override val commands: List<String> = listOf(),
    override val signalTime: Long = System.currentTimeMillis()
) : WaitSignal {
    companion object {
        fun FunctionInfo.toWaitSignal(
            id: String,
            waitCommandId: String,
            commandId: String,
            aggregateId: AggregateId,
            stage: CommandStage,
            isLastProjection: Boolean = false,
            aggregateVersion: Int? = null,
            errorCode: String = ErrorCodes.SUCCEEDED,
            errorMsg: String = ErrorCodes.SUCCEEDED_MESSAGE,
            bindingErrors: List<BindingError> = emptyList(),
            result: Map<String, Any> = emptyMap(),
            commands: List<String> = listOf(),
            signalTime: Long = System.currentTimeMillis()
        ): WaitSignal =
            SimpleWaitSignal(
                id = id,
                waitCommandId = waitCommandId,
                commandId = commandId,
                aggregateId = aggregateId,
                stage = stage,
                function = this.materialize(),
                aggregateVersion = aggregateVersion,
                isLastProjection = isLastProjection,
                errorCode = errorCode,
                errorMsg = errorMsg,
                bindingErrors = bindingErrors,
                result = result,
                commands = commands,
                signalTime = signalTime,
            )
    }

    override fun copyResult(result: Map<String, Any>): WaitSignal = copy(result = result)
}
