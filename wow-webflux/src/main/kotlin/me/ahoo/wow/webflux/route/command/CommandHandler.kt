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
import me.ahoo.wow.command.wait.timeout
import me.ahoo.wow.command.wait.withTimeout
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import org.reactivestreams.Publisher
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

class CommandHandler(
    private val commandGateway: CommandGateway,
    private val commandMessageExtractor: CommandMessageExtractor,
    private val commandWaitPolicy: CommandWaitPolicy
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
        val waitPlan = commandWaitPolicy.waitPlan(request, commandMessage)
            .withTimeout(commandWaitPolicy.timeout(request))
        return if (request.isSse()) {
            commandGateway.sendAndWaitStream(
                command = commandMessage,
                waitPlan = waitPlan
            ).ensureWaitTimeout(commandWaitTimeout = waitPlan.timeout)
        } else {
            commandGateway.sendAndWait(
                command = commandMessage,
                waitPlan = waitPlan
            ).ensureWaitTimeout(commandWaitTimeout = waitPlan.timeout)
        }
    }

    private fun Flux<CommandResult>.ensureWaitTimeout(commandWaitTimeout: Duration): Flux<CommandResult> =
        if (commandGateway.enforcesCommandWaitTimeout) {
            this
        } else {
            timeout(commandWaitTimeout)
        }

    private fun Mono<CommandResult>.ensureWaitTimeout(commandWaitTimeout: Duration): Mono<CommandResult> =
        if (commandGateway.enforcesCommandWaitTimeout) {
            this
        } else {
            timeout(commandWaitTimeout)
        }
}
