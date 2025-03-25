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

import me.ahoo.wow.api.command.validation.CommandValidator
import me.ahoo.wow.api.exception.DefaultErrorInfo
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.CommandResultException
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.openapi.aggregate.command.CommandRequestHeaders
import me.ahoo.wow.serialization.toObject
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.service.annotation.PostExchange
import java.net.URI

interface RestCommandGateway<RW, RB> {
    @PostExchange
    fun send(
        sendUri: URI,
        @RequestHeader(CommandRequestHeaders.COMMAND_TYPE, required = false)
        commandType: String,
        @RequestBody
        command: Any,
        @RequestHeader(CommandRequestHeaders.WAIT_STAGE, required = false)
        waitStage: CommandStage = CommandStage.PROCESSED,
        @RequestHeader(CommandRequestHeaders.WAIT_CONTEXT, required = false)
        waitContext: String? = null,
        @RequestHeader(CommandRequestHeaders.WAIT_PROCESSOR, required = false)
        waitProcessor: String? = null,
        @RequestHeader(CommandRequestHeaders.WAIT_TIME_OUT, required = false)
        waitTimeout: Long? = null,
        @RequestHeader(CommandRequestHeaders.TENANT_ID, required = false)
        tenantId: String? = null,
        @RequestHeader(CommandRequestHeaders.OWNER_ID, required = false)
        ownerId: String?,
        @RequestHeader(CommandRequestHeaders.AGGREGATE_ID, required = false)
        aggregateId: String? = null,
        @RequestHeader(CommandRequestHeaders.AGGREGATE_VERSION, required = false)
        aggregateVersion: Int? = null,
        @RequestHeader(CommandRequestHeaders.REQUEST_ID, required = false)
        requestId: String? = null,
        @RequestHeader(CommandRequestHeaders.LOCAL_FIRST, required = false)
        localFirst: Boolean? = null,
        @RequestHeader(CommandRequestHeaders.COMMAND_AGGREGATE_CONTEXT, required = false)
        context: String? = null,
        @RequestHeader(CommandRequestHeaders.COMMAND_AGGREGATE_NAME, required = false)
        aggregate: String? = null
    ): RW

    /**
     * Send a command request.
     *
     * @throws RestCommandGatewayException if the request fails
     */
    fun send(commandRequest: CommandRequest): RB {
        commandRequest.validate()
        val wrappedResponse = send(
            sendUri = commandRequest.sendUri,
            commandType = commandRequest.commandType,
            command = commandRequest.body,
            waitStage = commandRequest.waitStrategy.waitStage,
            waitContext = commandRequest.waitStrategy.waitContext,
            waitProcessor = commandRequest.waitStrategy.waitProcessor,
            waitTimeout = commandRequest.waitStrategy.waitTimeout,
            tenantId = commandRequest.tenantId,
            ownerId = commandRequest.ownerId,
            aggregateId = commandRequest.aggregateId,
            aggregateVersion = commandRequest.aggregateVersion,
            requestId = commandRequest.requestId,
            localFirst = commandRequest.localFirst,
            context = commandRequest.context,
            aggregate = commandRequest.aggregate
        )
        return unwrapResponse(commandRequest, wrappedResponse)
    }

    fun unwrapResponse(commandRequest: CommandRequest, response: RW): RB

    companion object {
        fun CommandRequest.validate() {
            if (body is CommandValidator) {
                body.validate()
            }
        }

        fun WebClientResponseException.toException(request: CommandRequest): RestCommandGatewayException {
            val errorCode = this.headers.getFirst(CommandRequestHeaders.WOW_ERROR_CODE).orEmpty()
            val responseBody = this.responseBodyAsString
            if (responseBody.isBlank()) {
                return RestCommandGatewayException(
                    request = request,
                    errorCode = errorCode,
                    errorMsg = this.message,
                    cause = this
                )
            }

            try {
                responseBody.toObject<CommandResult>().let {
                    return RestCommandGatewayException(
                        request = request,
                        errorCode = it.errorCode,
                        errorMsg = it.errorMsg,
                        cause = CommandResultException(it, this),
                        bindingErrors = it.bindingErrors
                    )
                }
            } catch (ignore: Throwable) {
                // ignore
            }

            try {
                responseBody.toObject<DefaultErrorInfo>().let {
                    return RestCommandGatewayException(
                        request = request,
                        errorCode = it.errorCode,
                        errorMsg = it.errorMsg,
                        cause = this,
                        bindingErrors = it.bindingErrors
                    )
                }
            } catch (ignore: Throwable) {
                // ignore
            }

            return RestCommandGatewayException(
                request = request,
                errorCode = errorCode,
                errorMsg = this.message,
                cause = this
            )
        }
    }
}
