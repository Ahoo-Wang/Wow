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

package me.ahoo.wow.webflux.route.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import org.reactivestreams.Publisher
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import java.time.Duration

class CommandHandler(
    private val commandGateway: CommandGateway,
    private val commandMessageExtractor: CommandMessageExtractor,
    private val timeout: Duration = DEFAULT_TIME_OUT
) {

    fun handle(
        request: ServerRequest,
        commandBody: Any,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
    ): Flux<CommandResult> {
        return commandMessageExtractor.extract(
            aggregateRouteMetadata = aggregateRouteMetadata,
            request = request,
            commandBody = commandBody
        ).flatMapMany {
            sendCommand(it, request)
        }
    }

    private fun sendCommand(commandMessage: CommandMessage<Any>, request: ServerRequest): Publisher<CommandResult> {
        val stage: CommandStage = request.getCommandStage()
        val waitContext = request.getWaitContext().ifBlank {
            commandMessage.contextName
        }
        val commandWaitTimeout = request.getWaitTimeout(timeout)
        val waitStrategy = WaitingForStage.stage(
            stage = stage,
            contextName = waitContext,
            processorName = request.getWaitProcessor(),
            functionName = request.getWaitFunction()
        )

        if (request.isSse()) {
            return commandGateway.sendAndWaitStream(
                command = commandMessage,
                waitStrategy = waitStrategy
            ).timeout(commandWaitTimeout)
        }
        return commandGateway.sendAndWait(
            command = commandMessage,
            waitStrategy = waitStrategy
        ).timeout(commandWaitTimeout)
    }
}
