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

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.command.CommandHeaders
import me.ahoo.wow.webflux.route.command.CommandParser.parse
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.*

fun ServerRequest.getCommandStage(): CommandStage {
    return headers().firstHeader(CommandHeaders.WAIT_STAGE).ifNotBlank { stage ->
        CommandStage.valueOf(stage.uppercase(Locale.getDefault()))
    } ?: CommandStage.PROCESSED
}

fun ServerRequest.getWaitContext(): String {
    return headers().firstHeader(CommandHeaders.WAIT_CONTEXT).orEmpty()
}

fun ServerRequest.getWaitProcessor(): String {
    return headers().firstHeader(CommandHeaders.WAIT_PROCESSOR).orEmpty()
}

fun ServerRequest.getWaitTimeout(default: Duration = DEFAULT_TIME_OUT): Duration {
    return headers().firstHeader(CommandHeaders.WAIT_TIME_OUT)?.toLongOrNull()?.let {
        Duration.ofMillis(it)
    } ?: default
}

class CommandHandler(
    private val commandGateway: CommandGateway,
    private val timeout: Duration = DEFAULT_TIME_OUT
) {

    fun handle(
        request: ServerRequest,
        commandBody: Any,
        aggregateMetadata: AggregateMetadata<*, *>,
    ): Mono<CommandResult> {
        val commandWaitTimeout = request.getWaitTimeout(timeout)
        return request.parse(
            aggregateMetadata = aggregateMetadata,
            commandBody = commandBody
        ).flatMap {
            val stage: CommandStage = request.getCommandStage()
            if (CommandStage.SENT == stage) {
                commandGateway.sendAndWaitForSent(it)
            } else {
                val waitContext = request.getWaitContext().ifBlank {
                    it.contextName
                }
                commandGateway.sendAndWait(
                    command = it,
                    waitStrategy = WaitingFor.stage(
                        stage = stage,
                        contextName = waitContext,
                        processorName = request.getWaitProcessor()
                    )
                )
            }.timeout(commandWaitTimeout)
        }
    }
}
