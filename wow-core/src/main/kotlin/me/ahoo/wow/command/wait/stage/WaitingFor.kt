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
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandStageCapable
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.injectWaitStrategy
import java.util.*

interface WaitingFor : WaitStrategy, CommandStageCapable {

    override fun inject(commandWaitEndpoint: CommandWaitEndpoint, header: Header) {
        header.injectWaitStrategy(commandWaitEndpoint.endpoint, this)
    }

    companion object {
        fun sent(): WaitingFor = WaitingForSent()
        fun processed(): WaitingFor = WaitingForProcessed()

        fun snapshot(): WaitingFor = WaitingForSnapshot()

        fun projected(contextName: String, processorName: String = "", functionName: String = ""): WaitingFor =
            WaitingForProjected(
                contextName = contextName,
                processorName = processorName,
                functionName = functionName
            )

        fun eventHandled(contextName: String, processorName: String = "", functionName: String = ""): WaitingFor =
            WaitingForEventHandled(
                contextName = contextName,
                processorName = processorName,
                functionName = functionName
            )

        fun sagaHandled(contextName: String, processorName: String = "", functionName: String = ""): WaitingFor =
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
        ): WaitingFor {
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
        ): WaitingFor =
            stage(
                stage = CommandStage.valueOf(stage.uppercase(Locale.getDefault())),
                contextName = contextName,
                processorName = processorName,
                functionName = functionName
            )
    }
}
