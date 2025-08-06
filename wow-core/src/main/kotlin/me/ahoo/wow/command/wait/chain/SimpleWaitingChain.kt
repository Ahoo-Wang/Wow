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

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.chain.SimpleWaitingForChain.Companion.COMMAND_WAIT_CHAIN
import me.ahoo.wow.command.wait.extractCommandWaitEndpoint
import me.ahoo.wow.command.wait.isWaitingForFunction
import me.ahoo.wow.command.wait.propagateCommandWaitEndpoint
import me.ahoo.wow.command.wait.propagateWaitCommandId
import me.ahoo.wow.serialization.toJsonString

class SimpleWaitingChain(
    override val waitCommandId: String,
    override val function: NamedFunctionInfoData,
    override val next: WaitingTailNode
) : WaitingChain {
    companion object {
        const val TYPE = "simple"
    }

    @field:JsonIgnore
    override val stage: CommandStage = CommandStage.SAGA_HANDLED

    override fun propagate(commandWaitEndpoint: String, header: Header) {
        header.propagateWaitCommandId(waitCommandId)
            .propagateCommandWaitEndpoint(commandWaitEndpoint)
            .with(COMMAND_WAIT_CHAIN, this.toJsonString())
    }

    override fun propagate(header: Header, upstream: Message<*, *>) {
        val commandWaitEndpoint = upstream.header.extractCommandWaitEndpoint() ?: return
        if (upstream is CommandMessage<*>) {
            propagate(commandWaitEndpoint, header)
        } else {
            next.propagate(commandWaitEndpoint, header)
        }
    }

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
}

class WaitingTailNode(
    override val waitCommandId: String,
    override val stage: CommandStage,
    override val function: NamedFunctionInfoData? = null
) : WaitingChain {

    @field:JsonIgnore
    override val next: WaitingChain? = null

    companion object {
        const val TYPE = "tail"
    }
}
