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

package me.ahoo.wow.apiclient

import me.ahoo.coapi.api.CoApi
import me.ahoo.wow.apiclient.RestCommandGatewayClient.Companion.COMMAND_SEND_ENDPOINT
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.CommandResultException
import me.ahoo.wow.command.rest.RestCommandGateway
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.openapi.command.CommandHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Mono
import java.net.URI

@CoApi
interface RestCommandGatewayClient : RestCommandGateway {
    companion object {
        const val COMMAND_SEND_ENDPOINT = "wow/command/send"
    }

    @PostExchange(COMMAND_SEND_ENDPOINT)
    fun send(
        sendUri: URI,
        @RequestHeader(CommandHeaders.COMMAND_TYPE, required = false)
        commandType: String,
        @RequestBody
        command: Any,
        @RequestHeader(CommandHeaders.WAIT_STAGE, required = false)
        waitStage: CommandStage = CommandStage.PROCESSED,
        @RequestHeader(CommandHeaders.WAIT_CONTEXT, required = false)
        waitContext: String? = null,
        @RequestHeader(CommandHeaders.WAIT_PROCESSOR, required = false)
        waitProcessor: String? = null,
        @RequestHeader(CommandHeaders.WAIT_TIME_OUT, required = false)
        waitTimeout: Long? = null,
        @RequestHeader(CommandHeaders.TENANT_ID, required = false)
        tenantId: String? = null,
        @RequestHeader(CommandHeaders.AGGREGATE_ID, required = false)
        aggregateId: String? = null,
        @RequestHeader(CommandHeaders.AGGREGATE_VERSION, required = false)
        aggregateVersion: Int? = null,
        @RequestHeader(CommandHeaders.REQUEST_ID, required = false)
        requestId: String? = null,
        @RequestHeader(CommandHeaders.COMMAND_AGGREGATE_CONTEXT, required = false)
        context: String? = null,
        @RequestHeader(CommandHeaders.COMMAND_AGGREGATE_NAME, required = false)
        aggregate: String? = null
    ): Mono<ResponseEntity<CommandResult>>

    override fun send(commandRequest: RestCommandGateway.CommandRequest): Mono<CommandResult> {
        return send(
            sendUri = commandRequest.resolveSendUri(),
            commandType = commandRequest.resolveCommandType(),
            command = commandRequest.body,
            waitStage = commandRequest.waitStrategy.waitStage,
            waitContext = commandRequest.waitStrategy.waitContext,
            waitProcessor = commandRequest.waitStrategy.waitProcessor,
            waitTimeout = commandRequest.waitStrategy.waitTimeout,
            tenantId = commandRequest.tenantId,
            aggregateId = commandRequest.aggregateId,
            aggregateVersion = commandRequest.aggregateVersion,
            requestId = commandRequest.requestId,
            context = commandRequest.context,
            aggregate = commandRequest.aggregate
        ).mapNotNull<CommandResult> {
            it.body
        }.onErrorMap(WebClientResponseException::class.java) {
            val commandResult = checkNotNull(it.getResponseBodyAs(CommandResult::class.java))
            CommandResultException(commandResult, it)
        }
    }
}

fun RestCommandGateway.CommandRequest.resolveCommandType(): String {
    return commandType ?: body.javaClass.name
}

fun RestCommandGateway.CommandRequest.resolveServiceId(): String {
    val commandContext = this.context
    if (!commandContext.isNullOrBlank()) {
        return commandContext
    }
    return MetadataSearcher.scopeContext.requiredSearch(resolveCommandType()).contextName
}

fun RestCommandGateway.CommandRequest.resolveSendUri(): URI {
    val serviceHost = serviceUri ?: "http://${resolveServiceId()}"
    return URI.create("$serviceHost/$COMMAND_SEND_ENDPOINT")
}
