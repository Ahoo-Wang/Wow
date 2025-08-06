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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.COMMAND_WAIT_PREFIX
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.extractWaitingChainTail
import me.ahoo.wow.command.wait.extractWaitFunction
import me.ahoo.wow.command.wait.propagateCommandWaitEndpoint
import me.ahoo.wow.command.wait.propagateWaitFunction
import me.ahoo.wow.infra.ifNotBlank

class SimpleWaitingChain(
    val tail: WaitingChainTail,
    override val function: NamedFunctionInfoData,
) : WaitStrategy.FunctionMaterialized {
    override val stage: CommandStage
        get() = CommandStage.SAGA_HANDLED

    override fun shouldPropagate(upstream: Message<*, *>): Boolean {
        return true
    }

    override fun propagate(commandWaitEndpoint: String, header: Header) {
        header.propagateWaitFunction(function)
            .propagateWaitChain(SIMPLE_CHAIN)
        tail.propagate(commandWaitEndpoint, header)
    }

    override fun propagate(header: Header, upstream: Message<*, *>) {
        if (upstream is CommandMessage) {
            super.propagate(header, upstream)
        } else {
            tail.propagate(header, upstream)
        }
    }

    companion object {
        const val COMMAND_WAIT_CHAIN = "${COMMAND_WAIT_PREFIX}chain"
        const val SIMPLE_CHAIN = "simple"

        fun Header.propagateWaitChain(chain: String): Header {
            return with(COMMAND_WAIT_CHAIN, chain)
        }

        fun Header.extractWaitChain(): String? {
            return this[COMMAND_WAIT_CHAIN]
        }

        fun Header.extractSimpleWaitingChain(): WaitStrategy.FunctionMaterialized? {
            if (extractWaitChain() != SIMPLE_CHAIN) {
                return extractWaitingChainTail()
            }
            val tail = extractWaitingChainTail() ?: return null
            val function = extractWaitFunction()
            return SimpleWaitingChain(tail, function)
        }
    }
}

class WaitingChainTail(
    override val stage: CommandStage,
    override val function: NamedFunctionInfoData
) : WaitStrategy.FunctionMaterialized {
    override fun propagate(commandWaitEndpoint: String, header: Header) {
        header.propagateCommandWaitEndpoint(commandWaitEndpoint)
            .with(COMMAND_WAIT_TAIL_STAGE, stage.name)
        function.contextName.ifNotBlank {
            header.with(COMMAND_WAIT_TAIL_CONTEXT, it)
        }
        function.processorName.ifNotBlank {
            header.with(COMMAND_WAIT_TAIL_PROCESSOR, it)
        }
        function.name.ifNotBlank {
            header.with(COMMAND_WAIT_TAIL_FUNCTION, it)
        }
    }

    companion object {
        const val COMMAND_WAIT_TAIL_PREFIX = "${COMMAND_WAIT_PREFIX}_tail_"
        const val COMMAND_WAIT_TAIL_STAGE = "${COMMAND_WAIT_TAIL_PREFIX}stage"
        const val COMMAND_WAIT_TAIL_CONTEXT = "${COMMAND_WAIT_TAIL_PREFIX}context"
        const val COMMAND_WAIT_TAIL_PROCESSOR = "${COMMAND_WAIT_TAIL_PREFIX}processor"
        const val COMMAND_WAIT_TAIL_FUNCTION = "${COMMAND_WAIT_TAIL_PREFIX}function"
        fun Header.extractWaitingChainTail(): WaitingChainTail? {
            val stage = this[COMMAND_WAIT_TAIL_STAGE] ?: return null
            val context = this[COMMAND_WAIT_TAIL_CONTEXT].orEmpty()
            val processor = this[COMMAND_WAIT_TAIL_PROCESSOR].orEmpty()
            val function = this[COMMAND_WAIT_TAIL_FUNCTION].orEmpty()
            return WaitingChainTail(
                stage = CommandStage.valueOf(stage),
                function = NamedFunctionInfoData(
                    contextName = context,
                    processorName = processor,
                    name = function
                )
            )
        }
    }
}
