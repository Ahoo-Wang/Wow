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

interface SignalTimeCapable {
    val signalTime: Long
}

interface NullableAggregateVersionCapable {
    val aggregateVersion: Int?
}

interface WaitSignal :
    Identifier,
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

    fun copyResult(result: Map<String, Any>): WaitSignal
}

data class SimpleWaitSignal(
    override val id: String,
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
        ): WaitSignal {
            return SimpleWaitSignal(
                id = id,
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
                signalTime = signalTime
            )
        }
    }

    override fun copyResult(result: Map<String, Any>): WaitSignal {
        return copy(result = result)
    }
}
