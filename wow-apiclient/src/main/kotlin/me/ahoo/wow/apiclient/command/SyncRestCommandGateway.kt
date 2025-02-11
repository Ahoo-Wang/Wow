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

package me.ahoo.wow.apiclient.command

import me.ahoo.coapi.api.CoApi
import me.ahoo.wow.apiclient.command.RestCommandGateway.Companion.toException
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.service.annotation.PostExchange
import java.net.URI

@CoApi
interface SyncRestCommandGateway : RestCommandGateway<ResponseEntity<CommandResult>, CommandResult> {
    @PostExchange
    override fun send(
        sendUri: URI,
        @RequestHeader(CommandRequestHeaders.COMMAND_TYPE, required = false)
        commandType: String,
        @RequestBody
        command: Any,
        @RequestHeader(CommandRequestHeaders.WAIT_STAGE, required = false)
        waitStage: CommandStage,
        @RequestHeader(CommandRequestHeaders.WAIT_CONTEXT, required = false)
        waitContext: String?,
        @RequestHeader(CommandRequestHeaders.WAIT_PROCESSOR, required = false)
        waitProcessor: String?,
        @RequestHeader(CommandRequestHeaders.WAIT_TIME_OUT, required = false)
        waitTimeout: Long?,
        @RequestHeader(CommandRequestHeaders.TENANT_ID, required = false)
        tenantId: String?,
        @RequestHeader(CommandRequestHeaders.OWNER_ID, required = false)
        ownerId: String?,
        @RequestHeader(CommandRequestHeaders.AGGREGATE_ID, required = false)
        aggregateId: String?,
        @RequestHeader(CommandRequestHeaders.AGGREGATE_VERSION, required = false)
        aggregateVersion: Int?,
        @RequestHeader(CommandRequestHeaders.REQUEST_ID, required = false)
        requestId: String?,
        @RequestHeader(CommandRequestHeaders.LOCAL_FIRST, required = false)
        localFirst: Boolean?,
        @RequestHeader(CommandRequestHeaders.COMMAND_AGGREGATE_CONTEXT, required = false)
        context: String?,
        @RequestHeader(CommandRequestHeaders.COMMAND_AGGREGATE_NAME, required = false)
        aggregate: String?
    ): ResponseEntity<CommandResult>

    override fun send(commandRequest: CommandRequest): CommandResult {
        try {
            return super.send(commandRequest)
        } catch (webclientResponseError: WebClientResponseException) {
            throw webclientResponseError.toException(commandRequest)
        }
    }

    override fun unwrapResponse(
        commandRequest: CommandRequest,
        response: ResponseEntity<CommandResult>
    ): CommandResult {
        return checkNotNull(response.body)
    }
}
