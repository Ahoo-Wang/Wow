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
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.COMMAND_WAIT_PREFIX
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.propagateCommandWaitEndpoint
import me.ahoo.wow.infra.ifNotBlank

/**
 * Represents the tail (final) stage in a waiting chain.
 * This class encapsulates the final stage and function criteria that a waiting chain
 * should wait for after completing the main processing stages.
 *
 * @param stage The command processing stage to wait for.
 * @param function The function criteria for the stage (may be empty for non-function stages).
 */
class WaitingChainTail(
    override val stage: CommandStage,
    override val function: NamedFunctionInfoData
) : WaitStrategy.FunctionMaterialized {
    override fun propagate(
        commandWaitEndpoint: String,
        header: Header
    ) {
        header
            .propagateCommandWaitEndpoint(commandWaitEndpoint)
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
        /**
         * Prefix for all waiting chain tail header keys.
         */
        const val COMMAND_WAIT_TAIL_PREFIX = "${COMMAND_WAIT_PREFIX}tail_"

        /**
         * Header key for storing the tail stage.
         */
        const val COMMAND_WAIT_TAIL_STAGE = "${COMMAND_WAIT_TAIL_PREFIX}stage"

        /**
         * Header key for storing the tail context name.
         */
        const val COMMAND_WAIT_TAIL_CONTEXT = "${COMMAND_WAIT_TAIL_PREFIX}context"

        /**
         * Header key for storing the tail processor name.
         */
        const val COMMAND_WAIT_TAIL_PROCESSOR = "${COMMAND_WAIT_TAIL_PREFIX}processor"

        /**
         * Header key for storing the tail function name.
         */
        const val COMMAND_WAIT_TAIL_FUNCTION = "${COMMAND_WAIT_TAIL_PREFIX}function"

        /**
         * Extracts a waiting chain tail from the message header.
         *
         * @return A WaitingChainTail if the tail stage is found, null otherwise.
         */
        fun Header.extractWaitingChainTail(): WaitingChainTail? {
            val stage = this[COMMAND_WAIT_TAIL_STAGE] ?: return null
            val context = this[COMMAND_WAIT_TAIL_CONTEXT].orEmpty()
            val processor = this[COMMAND_WAIT_TAIL_PROCESSOR].orEmpty()
            val function = this[COMMAND_WAIT_TAIL_FUNCTION].orEmpty()
            return WaitingChainTail(
                stage = CommandStage.valueOf(stage),
                function =
                NamedFunctionInfoData(
                    contextName = context,
                    processorName = processor,
                    name = function,
                ),
            )
        }

        /**
         * Converts a CommandStage to a WaitingChainTail with optional function criteria.
         * For stages that require function waiting, the provided function is used.
         * For stages that don't require function waiting, an empty function is used.
         *
         * @param function The function criteria for stages that require it. Defaults to empty.
         * @return A WaitingChainTail for this stage.
         */
        fun CommandStage.toWaitingChainTail(
            function: NamedFunctionInfoData = NamedFunctionInfoData.EMPTY
        ): WaitingChainTail {
            if (shouldWaitFunction) {
                return WaitingChainTail(
                    stage = this,
                    function = function,
                )
            }
            return WaitingChainTail(
                stage = this,
                function = NamedFunctionInfoData.EMPTY,
            )
        }
    }

    fun CommandStage.toWaitingChainTail(function: NamedFunctionInfoData = NamedFunctionInfoData.EMPTY): WaitingChainTail {
        if (shouldWaitFunction) {
            return WaitingChainTail(
                stage = this,
                function = function,
            )
        }
        return WaitingChainTail(
            stage = this,
            function = NamedFunctionInfoData.EMPTY,
        )
    }
}
