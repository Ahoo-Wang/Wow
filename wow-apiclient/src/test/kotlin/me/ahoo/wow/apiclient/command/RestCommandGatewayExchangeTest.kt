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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.validation.CommandValidator
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.api.exception.DefaultErrorInfo
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.CommandResultException
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.rest.WowHeaders
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

/**
 * The command gateways against a server stub: the result comes back unwrapped, a rejection comes back as a
 * [RestCommandGatewayException] carrying the server's error, whatever form the server sent it in, and a command that
 * validates itself is rejected before anything is sent.
 */
class RestCommandGatewayExchangeTest {
    private var status: HttpStatus = HttpStatus.OK
    private var responseHeaders: Map<String, String> = emptyMap()
    private var responseBody: String = ""
    private var sent = 0

    private val factory = HttpServiceProxyFactory.builderFor(
        WebClientAdapter.create(
            WebClient.builder().exchangeFunction { _ ->
                sent++
                val response = ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                responseHeaders.forEach { (name, value) -> response.header(name, value) }
                Mono.just(response.body(responseBody).build())
            }.build()
        )
    ).build()
    private val reactive = factory.createClient(ReactiveRestCommandGateway::class.java)
    private val sync = factory.createClient(SyncRestCommandGateway::class.java)
    private val request = CommandRequest(body = Body("value"), serviceUri = "http://service", type = "command-type")

    @Test
    fun `a processed command returns its result`() {
        respond(HttpStatus.OK, commandResult().toJsonString())

        reactive.send(request).test()
            .consumeNextWith { it.commandId.assert().isEqualTo("command-1") }
            .verifyComplete()
        sync.send(request).commandId.assert().isEqualTo("command-1")
    }

    @Test
    fun `a rejection with a command result keeps its error and binding errors`() {
        val bindingErrors = listOf(BindingError("name", "must not be blank"))
        respond(
            HttpStatus.BAD_REQUEST,
            commandResult(errorCode = "IllegalArgument", errorMsg = "bad command", bindingErrors = bindingErrors)
                .toJsonString(),
        )

        assertRejected(errorCode = "IllegalArgument", errorMsg = "bad command") {
            it.bindingErrors.assert().isEqualTo(bindingErrors)
            it.cause.assert().isInstanceOf(CommandResultException::class.java)
        }
    }

    @Test
    fun `a rejection with an error info keeps its error`() {
        respond(HttpStatus.NOT_FOUND, DefaultErrorInfo("NotFound", "no such aggregate").toJsonString())

        assertRejected(errorCode = "NotFound", errorMsg = "no such aggregate") {
            it.cause.assert().isInstanceOf(WebClientResponseException::class.java)
        }
    }

    @Test
    fun `a rejection without a body takes the error code header`() {
        respond(HttpStatus.CONFLICT, "", mapOf(WowHeaders.ERROR_CODE to "VersionConflict"))

        assertRejected(errorCode = "VersionConflict", errorMsg = null)
    }

    @Test
    fun `a rejection with a body that is neither a result nor an error info takes the error code header`() {
        respond(HttpStatus.INTERNAL_SERVER_ERROR, "[1,2]", mapOf(WowHeaders.ERROR_CODE to "Internal"))

        assertRejected(errorCode = "Internal", errorMsg = null)
    }

    @Test
    fun `a command that fails its own validation is not sent`() {
        val invalid = CommandRequest(body = SelfValidating(), serviceUri = "http://service", type = "command-type")

        assertThrows<IllegalArgumentException> { reactive.send(invalid) }
        assertThrows<IllegalArgumentException> { sync.send(invalid) }
        sent.assert().isZero()
    }

    @Test
    fun `the header-per-parameter send defaults every optional header`() {
        respond(HttpStatus.OK, commandResult().toJsonString())

        reactive.send(
            sendUri = request.sendUri,
            commandType = "command-type",
            command = request.body,
            ownerId = null,
            spaceId = null
        )
            .block()!!.body!!.commandId.assert().isEqualTo("command-1")
        sync.send(
            sendUri = request.sendUri,
            commandType = "command-type",
            command = request.body,
            ownerId = null,
            spaceId = null
        )
            .body!!.commandId.assert().isEqualTo("command-1")
    }

    @Test
    fun `a command is sent to its context's service unless a service uri is given`() {
        CommandRequest(body = Body("value"), context = "order-service", type = "command-type").sendUri.toString()
            .assert().isEqualTo("http://order-service/$COMMAND_SEND_ENDPOINT")
        CommandRequest(body = MockCreateAggregate("id", "data")).sendUri.toString()
            .assert().isEqualTo(
                "http://${requiredNamedAggregate<MockCreateAggregate>().contextName}/$COMMAND_SEND_ENDPOINT"
            )
        request.sendUri.toString().assert().isEqualTo("http://service/$COMMAND_SEND_ENDPOINT")
    }

    private fun assertRejected(
        errorCode: String,
        errorMsg: String?,
        verify: (RestCommandGatewayException) -> Unit = {},
    ) {
        val reactiveError = assertThrows<RestCommandGatewayException> { reactive.send(request).block() }
        val syncError = assertThrows<RestCommandGatewayException> { sync.send(request) }
        listOf(reactiveError, syncError).forEach { error ->
            error.request.assert().isSameAs(request)
            error.errorCode.assert().isEqualTo(errorCode)
            if (errorMsg != null) {
                error.errorMsg.assert().isEqualTo(errorMsg)
            } else {
                error.errorMsg.assert().isNotBlank()
            }
            error.message.assert().isEqualTo("[${error.errorCode}] - ${error.errorMsg}")
            verify(error)
        }
    }

    private fun respond(status: HttpStatus, body: String, headers: Map<String, String> = emptyMap()) {
        this.status = status
        this.responseBody = body
        this.responseHeaders = headers
    }

    private fun commandResult(
        errorCode: String = "Ok",
        errorMsg: String = "",
        bindingErrors: List<BindingError> = emptyList(),
    ) = CommandResult(
        id = "result-1",
        waitCommandId = "command-1",
        stage = CommandStage.PROCESSED,
        contextName = "context",
        aggregateName = "aggregate",
        tenantId = "tenant",
        aggregateId = "aggregate-1",
        requestId = "request-1",
        commandId = "command-1",
        function = FunctionInfoData(FunctionKind.COMMAND, "context", "Aggregate", "onCommand"),
        errorCode = errorCode,
        errorMsg = errorMsg,
        bindingErrors = bindingErrors,
    )

    private data class Body(val value: String)

    private class SelfValidating : CommandValidator {
        override fun validate() {
            throw IllegalArgumentException("invalid")
        }
    }
}
