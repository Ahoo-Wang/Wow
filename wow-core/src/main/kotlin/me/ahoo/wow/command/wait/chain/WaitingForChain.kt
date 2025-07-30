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

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.function.FunctionNameCapable
import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.command.wait.COMMAND_WAIT_PREFIX
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandStageCapable
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.command.wait.propagateCommandWaitEndpoint
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toList


interface WaitingForChainNodesCapable {
    val nodes: List<WaitingNode>
}

class WaitingForChain(override val nodes: List<WaitingNode>) : WaitingFor(), WaitingForChainNodesCapable {
    override val materialized: WaitStrategy.Materialized by lazy {
        Materialized(nodes)
    }

    override fun next(signal: WaitSignal) {
        TODO("Not yet implemented")
    }

    override fun isPreviousSignal(signal: WaitSignal): Boolean {
        TODO("Not yet implemented")
    }


    class Materialized(override val nodes: List<WaitingNode>) : WaitStrategy.Materialized,
        WaitingForChainNodesCapable {
        override fun propagate(commandWaitEndpoint: String, header: Header) {
            header.propagateCommandWaitEndpoint(commandWaitEndpoint)
                .with(COMMAND_WAIT_CHAIN, nodes.toJsonString())
        }

        override fun shouldNotify(processingStage: CommandStage): Boolean {
            return nodes.any { it.stage == processingStage }
        }

        override fun shouldNotify(signal: WaitSignal): Boolean {
            TODO("Not yet implemented")
        }
    }

    companion object {
        const val COMMAND_WAIT_CHAIN = "${COMMAND_WAIT_PREFIX}chain"
        fun Header.extractWaitingForChain(): Materialized? {
            val chain = this[COMMAND_WAIT_CHAIN] ?: return null
            val nodes = chain.toList<WaitingForChainNode>()
            return Materialized(
                nodes = nodes
            )
        }
    }
}