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

package me.ahoo.wow.messaging.propagation

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.command.wait.COMMAND_WAIT_CONTEXT
import me.ahoo.wow.command.wait.COMMAND_WAIT_ENDPOINT
import me.ahoo.wow.command.wait.COMMAND_WAIT_FUNCTION
import me.ahoo.wow.command.wait.COMMAND_WAIT_PROCESSOR
import me.ahoo.wow.command.wait.COMMAND_WAIT_STAGE

class WaitStrategyMessagePropagator : MessagePropagator {
    override fun inject(header: Header, upstream: Message<*, *>) {
        if (upstream !is CommandMessage<*>) {
            return
        }
        val upstreamHeader = upstream.header
        val commandWaitEndpoint = upstreamHeader[COMMAND_WAIT_ENDPOINT] ?: return
        header.with(COMMAND_WAIT_ENDPOINT, commandWaitEndpoint)
        val commandWaitStage = upstreamHeader[COMMAND_WAIT_STAGE].orEmpty()
        header.with(COMMAND_WAIT_STAGE, commandWaitStage)
        val commandWaitContext = upstreamHeader[COMMAND_WAIT_CONTEXT]
        if (!commandWaitContext.isNullOrBlank()) {
            header.with(COMMAND_WAIT_CONTEXT, commandWaitContext)
        }
        val commandWaitProcessor = upstreamHeader[COMMAND_WAIT_PROCESSOR]
        if (!commandWaitProcessor.isNullOrBlank()) {
            header.with(COMMAND_WAIT_PROCESSOR, commandWaitProcessor)
        }
        val commandWaitFunction = upstreamHeader[COMMAND_WAIT_FUNCTION]
        if (!commandWaitFunction.isNullOrBlank()) {
            header.with(COMMAND_WAIT_FUNCTION, commandWaitFunction)
        }
    }
}
