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

import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.chain.WaitingChainTail
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.toWaitingChainTail
import java.util.Locale

object CommandWait {
    fun sent(waitCommandId: String): WaitPlan =
        SimpleWaitPlan(waitCommandId, StageWaitTarget(CommandStage.SENT), supportVoidCommand = true)

    fun processed(waitCommandId: String): WaitPlan =
        SimpleWaitPlan(waitCommandId, StageWaitTarget(CommandStage.PROCESSED))

    fun snapshot(waitCommandId: String): WaitPlan =
        SimpleWaitPlan(waitCommandId, StageWaitTarget(CommandStage.SNAPSHOT))

    fun projected(
        waitCommandId: String,
        contextName: String,
        processorName: String = "",
        functionName: String = "",
    ): WaitPlan =
        SimpleWaitPlan(
            waitCommandId = waitCommandId,
            target = StageWaitTarget(
                CommandStage.PROJECTED,
                NamedFunctionInfoData(contextName, processorName, functionName),
            ),
        )

    fun eventHandled(
        waitCommandId: String,
        contextName: String,
        processorName: String = "",
        functionName: String = "",
    ): WaitPlan =
        SimpleWaitPlan(
            waitCommandId = waitCommandId,
            target = StageWaitTarget(
                CommandStage.EVENT_HANDLED,
                NamedFunctionInfoData(contextName, processorName, functionName),
            ),
        )

    fun sagaHandled(
        waitCommandId: String,
        contextName: String,
        processorName: String = "",
        functionName: String = "",
    ): WaitPlan =
        SimpleWaitPlan(
            waitCommandId = waitCommandId,
            target = StageWaitTarget(
                CommandStage.SAGA_HANDLED,
                NamedFunctionInfoData(contextName, processorName, functionName),
            ),
        )

    fun stage(
        waitCommandId: String,
        stage: CommandStage,
        contextName: String = "",
        processorName: String = "",
        functionName: String = "",
    ): WaitPlan =
        when (stage) {
            CommandStage.SENT -> sent(waitCommandId)
            CommandStage.PROCESSED -> processed(waitCommandId)
            CommandStage.SNAPSHOT -> snapshot(waitCommandId)
            CommandStage.PROJECTED -> projected(waitCommandId, contextName, processorName, functionName)
            CommandStage.EVENT_HANDLED -> eventHandled(waitCommandId, contextName, processorName, functionName)
            CommandStage.SAGA_HANDLED -> sagaHandled(waitCommandId, contextName, processorName, functionName)
        }

    fun stage(
        waitCommandId: String,
        stage: String,
        contextName: String = "",
        processorName: String = "",
        functionName: String = "",
    ): WaitPlan =
        stage(
            waitCommandId = waitCommandId,
            stage = CommandStage.valueOf(stage.uppercase(Locale.ROOT)),
            contextName = contextName,
            processorName = processorName,
            functionName = functionName,
        )

    fun chain(
        waitCommandId: String,
        function: NamedFunctionInfoData,
        tail: WaitingChainTail,
    ): WaitPlan =
        SimpleWaitPlan(
            waitCommandId = waitCommandId,
            target = ChainWaitTarget(function = function, tail = tail),
        )

    fun chain(
        waitCommandId: String,
        function: NamedFunctionInfoData,
        tailStage: CommandStage,
        tailFunction: NamedFunctionInfoData,
    ): WaitPlan =
        chain(waitCommandId, function, tailStage.toWaitingChainTail(tailFunction))
}
