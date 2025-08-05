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
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.messaging.function.NullableFunctionInfoCapable
import me.ahoo.wow.command.wait.COMMAND_WAIT_PREFIX
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandStageCapable
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.command.wait.extractCommandWaitEndpoint
import me.ahoo.wow.command.wait.isWaitingForFunction
import me.ahoo.wow.command.wait.propagateCommandWaitEndpoint
import me.ahoo.wow.command.wait.propagateCommandWaitId
import me.ahoo.wow.command.wait.requireExtractCommandWaitId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.ifNotBlank
import java.util.*

/**
 * Single command waiting strategy based on command execution phase
 */
abstract class WaitingForStage : WaitingFor(), CommandStageCapable {
    override val materialized: WaitStrategy.Materialized by lazy {
        Materialized(
            id = id,
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
        override val id: String,
        override val stage: CommandStage,
        override val function: NamedFunctionInfoData? = null
    ) : WaitStrategy.Materialized, CommandStageCapable, NullableFunctionInfoCapable<NamedFunctionInfoData> {
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
            return this.function.isWaitingForFunction(signal.function)
        }

        override fun propagate(header: Header, upstream: Message<*, *>) {
            if (upstream !is CommandMessage<*>) {
                return
            }
            val commandWaitEndpoint = upstream.header.extractCommandWaitEndpoint() ?: return
            propagate(commandWaitEndpoint, header)
        }

        override fun propagate(commandWaitEndpoint: String, header: Header) {
            header.propagateCommandWaitId(id)
                .propagateCommandWaitEndpoint(commandWaitEndpoint)
                .with(COMMAND_WAIT_STAGE, stage.name)
            val function = function ?: return
            function.contextName.ifNotBlank {
                header.with(COMMAND_WAIT_CONTEXT, it)
            }
            function.processorName.ifNotBlank {
                header.with(COMMAND_WAIT_PROCESSOR, it)
            }
            function.name.ifNotBlank {
                header.with(COMMAND_WAIT_FUNCTION, it)
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
            val id = requireExtractCommandWaitId()
            val context = this[COMMAND_WAIT_CONTEXT].orEmpty()
            val processor = this[COMMAND_WAIT_PROCESSOR].orEmpty()
            val function = this[COMMAND_WAIT_FUNCTION].orEmpty()
            return Materialized(
                id = id,
                stage = CommandStage.valueOf(stage),
                function = NamedFunctionInfoData(
                    contextName = context,
                    processorName = processor,
                    name = function
                )
            )
        }

        fun sent(commandWaitId: String = generateGlobalId()): WaitingForStage = WaitingForSent(commandWaitId)
        fun processed(commandWaitId: String = generateGlobalId()): WaitingForStage = WaitingForProcessed(commandWaitId)

        fun snapshot(commandWaitId: String = generateGlobalId()): WaitingForStage = WaitingForSnapshot(commandWaitId)

        fun projected(
            contextName: String,
            processorName: String = "",
            functionName: String = "",
            commandWaitId: String = generateGlobalId()
        ): WaitingForStage =
            WaitingForProjected(
                id = commandWaitId,
                function = NamedFunctionInfoData(
                    contextName = contextName,
                    processorName = processorName,
                    name = functionName
                )
            )

        fun eventHandled(
            contextName: String,
            processorName: String = "",
            functionName: String = "",
            commandWaitId: String = generateGlobalId()
        ): WaitingForStage =
            WaitingForEventHandled(
                id = commandWaitId,
                function = NamedFunctionInfoData(
                    contextName = contextName,
                    processorName = processorName,
                    name = functionName
                )
            )

        fun sagaHandled(
            contextName: String,
            processorName: String = "",
            functionName: String = "",
            commandWaitId: String = generateGlobalId()
        ): WaitingForStage =
            WaitingForSagaHandled(
                id = commandWaitId,
                function = NamedFunctionInfoData(
                    contextName = contextName,
                    processorName = processorName,
                    name = functionName
                )
            )

        fun stage(
            stage: CommandStage,
            contextName: String,
            processorName: String = "",
            functionName: String = "",
            commandWaitId: String = generateGlobalId()
        ): WaitingForStage {
            return when (stage) {
                CommandStage.SENT -> sent(commandWaitId)
                CommandStage.PROCESSED -> processed(commandWaitId)
                CommandStage.SNAPSHOT -> snapshot(commandWaitId)
                CommandStage.PROJECTED -> projected(contextName, processorName, functionName, commandWaitId)
                CommandStage.EVENT_HANDLED -> eventHandled(contextName, processorName, functionName, commandWaitId)
                CommandStage.SAGA_HANDLED -> sagaHandled(contextName, processorName, functionName, commandWaitId)
            }
        }

        fun stage(
            stage: String,
            contextName: String,
            processorName: String = "",
            functionName: String = "",
            commandWaitId: String = generateGlobalId()
        ): WaitingForStage =
            stage(
                stage = CommandStage.valueOf(stage.uppercase(Locale.getDefault())),
                contextName = contextName,
                processorName = processorName,
                functionName = functionName,
                commandWaitId = commandWaitId
            )
    }
}
