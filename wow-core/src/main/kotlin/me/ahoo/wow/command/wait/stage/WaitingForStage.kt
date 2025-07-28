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
import me.ahoo.wow.api.messaging.function.FunctionNameCapable
import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.command.wait.COMMAND_WAIT_ENDPOINT
import me.ahoo.wow.command.wait.COMMAND_WAIT_PREFIX
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandStageCapable
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.WaitingFor
import java.util.Locale

abstract class WaitingForStage : WaitingFor(), CommandStageCapable {
    override fun next(signal: WaitSignal) {
        nextSignal(signal)
        if (signal.stage == stage) {
            complete()
        }
    }

    override fun inject(commandWaitEndpoint: CommandWaitEndpoint, header: Header) {
        val waitingFor = this
        header.with(COMMAND_WAIT_ENDPOINT, commandWaitEndpoint.endpoint)
            .with(COMMAND_WAIT_STAGE, waitingFor.stage.name)
        if (waitingFor is ProcessorInfo) {
            header.with(COMMAND_WAIT_CONTEXT, waitingFor.contextName)
                .with(COMMAND_WAIT_PROCESSOR, waitingFor.processorName)
        }
        if (waitingFor is FunctionNameCapable) {
            header.with(COMMAND_WAIT_FUNCTION, waitingFor.functionName)
        }
    }

    data class Info(
        override val endpoint: String,
        override val stage: CommandStage,
        override val contextName: String,
        override val processorName: String,
        override val functionName: String
    ) : WaitStrategy.Info, CommandStageCapable, ProcessorInfo, FunctionNameCapable {
        override fun shouldNotify(processingStage: CommandStage): Boolean {
            return stage.shouldNotify(processingStage)
        }

        override fun shouldNotify(signal: WaitSignal): Boolean {
            return true
        }
    }

    companion object {
        const val COMMAND_WAIT_STAGE = "${COMMAND_WAIT_PREFIX}stage"
        const val COMMAND_WAIT_CONTEXT = "${COMMAND_WAIT_PREFIX}context"
        const val COMMAND_WAIT_PROCESSOR = "${COMMAND_WAIT_PREFIX}processor"
        const val COMMAND_WAIT_FUNCTION = "${COMMAND_WAIT_PREFIX}function"
        fun Header.extractWaitingForStage(): Info? {
            val commandWaitEndpoint = this[COMMAND_WAIT_ENDPOINT] ?: return null
            val stage = this[COMMAND_WAIT_STAGE].orEmpty()
            val context = this[COMMAND_WAIT_CONTEXT].orEmpty()
            val processor = this[COMMAND_WAIT_PROCESSOR].orEmpty()
            val function = this[COMMAND_WAIT_FUNCTION].orEmpty()
            return Info(
                endpoint = commandWaitEndpoint,
                stage = CommandStage.valueOf(stage),
                contextName = context,
                processorName = processor,
                functionName = function
            )
        }

        fun sent(): WaitingForStage = WaitingForSent()
        fun processed(): WaitingForStage = WaitingForProcessed()

        fun snapshot(): WaitingForStage = WaitingForSnapshot()

        fun projected(contextName: String, processorName: String = "", functionName: String = ""): WaitingForStage =
            WaitingForProjected(
                contextName = contextName,
                processorName = processorName,
                functionName = functionName
            )

        fun eventHandled(contextName: String, processorName: String = "", functionName: String = ""): WaitingForStage =
            WaitingForEventHandled(
                contextName = contextName,
                processorName = processorName,
                functionName = functionName
            )

        fun sagaHandled(contextName: String, processorName: String = "", functionName: String = ""): WaitingForStage =
            WaitingForSagaHandled(
                contextName = contextName,
                processorName = processorName,
                functionName = functionName
            )

        fun stage(
            stage: CommandStage,
            contextName: String,
            processorName: String = "",
            functionName: String = ""
        ): WaitingForStage {
            return when (stage) {
                CommandStage.SENT -> sent()
                CommandStage.PROCESSED -> processed()
                CommandStage.SNAPSHOT -> snapshot()
                CommandStage.PROJECTED -> projected(contextName, processorName, functionName)
                CommandStage.EVENT_HANDLED -> eventHandled(contextName, processorName, functionName)
                CommandStage.SAGA_HANDLED -> sagaHandled(contextName, processorName, functionName)
            }
        }

        fun stage(
            stage: String,
            contextName: String,
            processorName: String = "",
            functionName: String = ""
        ): WaitingForStage =
            stage(
                stage = CommandStage.valueOf(stage.uppercase(Locale.getDefault())),
                contextName = contextName,
                processorName = processorName,
                functionName = functionName
            )
    }
}
