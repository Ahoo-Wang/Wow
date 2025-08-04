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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionNameCapable
import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.command.wait.COMMAND_WAIT_PREFIX
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandStageCapable
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.command.wait.isWaitingForFunction
import me.ahoo.wow.command.wait.propagateCommandWaitEndpoint
import me.ahoo.wow.infra.ifNotBlank
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
        override val contextName: String = "",
        override val processorName: String = "",
        override val functionName: String = ""
    ) : WaitStrategy.Materialized, CommandStageCapable, ProcessorInfo, FunctionNameCapable {
        override fun shouldNotify(processingStage: CommandStage): Boolean {
            return stage.shouldNotify(processingStage)
        }

        override fun shouldNotify(signal: WaitSignal): Boolean {
            if (stage.isPrevious(signal.stage)) {
                return true
            }
            if (stage != signal.stage) {
                return false
            }
            return this.isWaitingForFunction(signal.function)
        }

        override fun shouldPropagate(upstream: Message<*, *>): Boolean {
            return upstream is CommandMessage<*>
        }

        override fun propagate(commandWaitEndpoint: String, header: Header) {
            header.propagateCommandWaitEndpoint(commandWaitEndpoint)
                .with(COMMAND_WAIT_STAGE, stage.name)
            contextName.ifNotBlank {
                header.with(COMMAND_WAIT_CONTEXT, contextName)
            }
            processorName.ifNotBlank {
                header.with(COMMAND_WAIT_PROCESSOR, processorName)
            }
            functionName.ifNotBlank {
                header.with(COMMAND_WAIT_FUNCTION, functionName)
            }
            functionName.ifNotBlank {
                header.with(COMMAND_WAIT_FUNCTION, functionName)
            }
        }
    }

    companion object {
        const val COMMAND_WAIT_STAGE = "${COMMAND_WAIT_PREFIX}stage"
        const val COMMAND_WAIT_CONTEXT = "${COMMAND_WAIT_PREFIX}context"
        const val COMMAND_WAIT_PROCESSOR = "${COMMAND_WAIT_PREFIX}processor"
        const val COMMAND_WAIT_FUNCTION = "${COMMAND_WAIT_PREFIX}function"
        fun Header.extractWaitingForStage(): Materialized? {
            val stage = this[COMMAND_WAIT_STAGE] ?: return null
            val context = this[COMMAND_WAIT_CONTEXT].orEmpty()
            val processor = this[COMMAND_WAIT_PROCESSOR].orEmpty()
            val function = this[COMMAND_WAIT_FUNCTION].orEmpty()
            return Materialized(
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
