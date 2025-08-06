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

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandStageCapable
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.command.wait.extractWaitFunction
import me.ahoo.wow.command.wait.extractWaitingStage
import me.ahoo.wow.command.wait.propagateCommandWaitEndpoint
import me.ahoo.wow.command.wait.propagateWaitFunction
import me.ahoo.wow.command.wait.propagateWaitingStage
import java.util.*

/**
 * Single command waiting strategy based on command execution phase
 */
abstract class WaitingForStage : WaitingFor(), CommandStageCapable {
    override val materialized: WaitStrategy.Materialized by lazy {
        Materialized(
            stage = stage
        )
    }

    override fun isPreviousSignal(signal: WaitSignal): Boolean {
        return stage.isPrevious(signal.stage)
    }

    override fun next(signal: WaitSignal) {
        nextSignal(signal)
        if (completed.not() && signal.stage == stage) {
            complete()
        }
    }

    data class Materialized(
        override val stage: CommandStage,
        override val function: NamedFunctionInfoData? = null
    ) : WaitStrategy.FunctionMaterialized {

        override fun propagate(commandWaitEndpoint: String, header: Header) {
            header.propagateCommandWaitEndpoint(commandWaitEndpoint)
                .propagateWaitingStage(stage)
                .propagateWaitFunction(function)
        }
    }

    companion object {
        fun Header.extractWaitingForStage(): Materialized? {
            val stage = extractWaitingStage() ?: return null
            val function = extractWaitFunction()
            return Materialized(
                stage = stage,
                function = function
            )
        }

        fun sent(commandWaitId: String): WaitingForStage = WaitingForSent(commandWaitId)
        fun processed(commandWaitId: String): WaitingForStage = WaitingForProcessed(commandWaitId)

        fun snapshot(commandWaitId: String): WaitingForStage = WaitingForSnapshot(commandWaitId)

        fun projected(
            waitCommandId: String,
            contextName: String,
            processorName: String = "",
            functionName: String = "",
        ): WaitingForStage =
            WaitingForProjected(
                waitCommandId = waitCommandId,
                function = NamedFunctionInfoData(
                    contextName = contextName,
                    processorName = processorName,
                    name = functionName
                )
            )

        fun eventHandled(
            waitCommandId: String,
            contextName: String,
            processorName: String = "",
            functionName: String = ""
        ): WaitingForStage =
            WaitingForEventHandled(
                waitCommandId = waitCommandId,
                function = NamedFunctionInfoData(
                    contextName = contextName,
                    processorName = processorName,
                    name = functionName
                )
            )

        fun sagaHandled(
            waitCommandId: String,
            contextName: String,
            processorName: String = "",
            functionName: String = "",
        ): WaitingForStage =
            WaitingForSagaHandled(
                waitCommandId = waitCommandId,
                function = NamedFunctionInfoData(
                    contextName = contextName,
                    processorName = processorName,
                    name = functionName
                )
            )

        fun stage(
            waitCommandId: String,
            stage: CommandStage,
            contextName: String,
            processorName: String = "",
            functionName: String = "",
        ): WaitingForStage {
            return when (stage) {
                CommandStage.SENT -> sent(waitCommandId)
                CommandStage.PROCESSED -> processed(waitCommandId)
                CommandStage.SNAPSHOT -> snapshot(waitCommandId)
                CommandStage.PROJECTED -> projected(waitCommandId, contextName, processorName, functionName)
                CommandStage.EVENT_HANDLED -> eventHandled(waitCommandId, contextName, processorName, functionName)
                CommandStage.SAGA_HANDLED -> sagaHandled(waitCommandId, contextName, processorName, functionName)
            }
        }

        fun stage(
            waitCommandId: String,
            stage: String,
            contextName: String,
            processorName: String = "",
            functionName: String = ""
        ): WaitingForStage =
            stage(
                waitCommandId = waitCommandId,
                stage = CommandStage.valueOf(stage.uppercase(Locale.getDefault())),
                contextName = contextName,
                processorName = processorName,
                functionName = functionName,
            )
    }
}
