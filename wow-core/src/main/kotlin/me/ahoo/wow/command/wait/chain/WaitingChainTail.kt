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
        const val COMMAND_WAIT_TAIL_PREFIX = "${COMMAND_WAIT_PREFIX}tail_"
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

        fun CommandStage.toWaitingChainTail(function: NamedFunctionInfoData = NamedFunctionInfoData.Companion.EMPTY): WaitingChainTail {
            if (shouldWaitFunction) {
                return WaitingChainTail(
                    stage = this,
                    function = NamedFunctionInfoData.EMPTY
                )
            }
            return WaitingChainTail(
                stage = this,
                function = function
            )
        }
    }
}
