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

package me.ahoo.wow.webflux.handler

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.webflux.route.CommandParser.parse
import me.ahoo.wow.webflux.route.appender.CommandHeaders
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.*

val DEFAULT_TIME_OUT: Duration = Duration.ofSeconds(30)
fun ServerRequest.getCommandStage(): CommandStage {
    val stage: CommandStage = headers().firstHeader(CommandHeaders.WAIT_STAGE)?.let { stage ->
        CommandStage.valueOf(stage.uppercase(Locale.getDefault()))
    } ?: CommandStage.PROCESSED
    return stage
}

class CommandHandler(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val commandGateway: CommandGateway,
    private val timeout: Duration = DEFAULT_TIME_OUT
) {

    fun handle(request: ServerRequest, commandBody: Any, aggregateId: String? = null): Mono<CommandResult> {
        val commandWaitTimeout = request.headers().firstHeader(CommandHeaders.WAIT_TIME_OUT)?.let {
            Duration.ofMillis(it.toLong())
        } ?: timeout
        return request.parse(
            aggregateMetadata = aggregateMetadata,
            commandBody = commandBody,
            aggregateId = aggregateId,
        )
            .flatMap {
                val stage: CommandStage = request.getCommandStage()
                if (CommandStage.SENT == stage) {
                    commandGateway.sendAndWaitForSent(it)
                } else {
                    commandGateway.sendAndWait(it, waitStrategy = WaitingFor.stage(it.contextName, stage))
                }.timeout(commandWaitTimeout)
            }
    }
}
