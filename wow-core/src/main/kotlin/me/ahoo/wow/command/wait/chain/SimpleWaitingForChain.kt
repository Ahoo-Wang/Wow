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

package me.ahoo.wow.command.wait.chain

import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.toWaitingChainTail
import me.ahoo.wow.command.wait.isWaitingForFunction
import me.ahoo.wow.command.wait.stage.WaitingForStage

class SimpleWaitingForChain(
    override val waitCommandId: String,
    override val materialized: SimpleWaitingChain
) : WaitingFor() {

    private val mainWaiting = WaitingForStage.sagaHandled(
        waitCommandId = waitCommandId,
        contextName = materialized.function.contextName,
        processorName = materialized.function.processorName,
        functionName = materialized.function.name
    )

    @Volatile
    private var mainWaitingSignal: WaitSignal? = null
    private val tailWaiting = mutableMapOf<String, WaitingForStage>()

    private fun tailWaitingCompleted(): Boolean {
        if (mainWaitingSignal == null) {
            return false
        }
        return tailWaiting.all { it.value.completed }
    }

    private fun ensureTailWaiting(commandId: String): WaitingForStage {
        val tail = materialized.tail
        var waitingForStage = tailWaiting[commandId]
        if (waitingForStage != null) {
            return waitingForStage
        }

        synchronized(this) {
            waitingForStage = tailWaiting[commandId]
            if (waitingForStage != null) {
                return waitingForStage
            }

            waitingForStage = WaitingForStage.stage(
                waitCommandId = commandId,
                stage = tail.stage,
                contextName = tail.function.contextName,
                processorName = tail.function.processorName,
                functionName = tail.function.name
            )
            tailWaiting[commandId] = waitingForStage
            return waitingForStage
        }
    }

    private fun mainNext(signal: WaitSignal) {
        mainWaiting.next(signal)
        if (!materialized.function.isWaitingForFunction(signal.function)) {
            return
        }
        mainWaitingSignal = signal
        signal.commands.forEach { commandId ->
            ensureTailWaiting(commandId)
        }
    }

    private fun tailNext(signal: WaitSignal) {
        ensureTailWaiting(signal.commandId).next(signal)
    }

    override fun next(signal: WaitSignal) {
        nextSignal(signal)
        if (waitCommandId == signal.commandId) {
            mainNext(signal)
        } else {
            tailNext(signal)
        }
        if (mainWaiting.completed && tailWaitingCompleted()) {
            complete()
        }
    }

    override fun isPreviousSignal(signal: WaitSignal): Boolean {
        return true
    }

    companion object {
        fun chain(
            waitCommandId: String,
            function: NamedFunctionInfoData,
            tailStage: CommandStage,
            tailFunction: NamedFunctionInfoData
        ): SimpleWaitingForChain {
            return SimpleWaitingForChain(
                waitCommandId = waitCommandId,
                materialized = SimpleWaitingChain(
                    function = function,
                    tail = tailStage.toWaitingChainTail(tailFunction)
                )
            )
        }
    }
}
