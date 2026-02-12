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
import me.ahoo.wow.command.wait.propagateWaitFunction

/**
 * A simple waiting chain that waits for saga handling completion followed by a tail stage.
 * This strategy combines waiting for saga processing with an additional stage/function criteria.
 * The chain waits for SAGA_HANDLED stage with specific function matching, then waits for
 * the tail stage/function combination.
 *
 * @param tail The tail stage and function to wait for after saga handling.
 * @param function The function criteria for saga handling stage.
 */
class SimpleWaitingChain(
    val tail: WaitingChainTail,
    override val function: NamedFunctionInfoData
) : WaitStrategy.FunctionMaterialized {
    override val stage: CommandStage
        get() = CommandStage.SAGA_HANDLED

    override fun shouldPropagate(upstream: Message<*, *>): Boolean = true

    override fun propagate(
        commandWaitEndpoint: String,
        header: Header
    ) {
        header
            .propagateWaitFunction(function)
            .propagateWaitChain(SIMPLE_CHAIN)
        tail.propagate(commandWaitEndpoint, header)
    }

    override fun propagate(
        header: Header,
        upstream: Message<*, *>
    ) {
        if (upstream is CommandMessage) {
            super.propagate(header, upstream)
        } else {
            tail.propagate(header, upstream)
        }
    }

    companion object {
        /**
         * Header key for storing the wait chain type.
         */
        const val COMMAND_WAIT_CHAIN = "${COMMAND_WAIT_PREFIX}chain"

        /**
         * Constant identifying a simple waiting chain.
         */
        const val SIMPLE_CHAIN = "simple"

        /**
         * Adds the wait chain type to the message header.
         *
         * @param chain The chain type identifier.
         * @return A new Header instance with the chain type added.
         */
        fun Header.propagateWaitChain(chain: String): Header = with(COMMAND_WAIT_CHAIN, chain)

        /**
         * Extracts the wait chain type from the message header.
         *
         * @return The chain type if present, null otherwise.
         */
        fun Header.extractWaitChain(): String? = this[COMMAND_WAIT_CHAIN]

        /**
         * Extracts a simple waiting chain from the message header.
         * Attempts to extract both simple chain and tail configurations.
         *
         * @return A SimpleWaitingChain if all required components are found, null otherwise.
         */
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
