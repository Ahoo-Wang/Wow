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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.CommandRouteMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.DefaultGlobalExceptionHandler
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunctions

/**
 * A command body the client got wrong is a `400 IllegalArgument` on every command route. A route with path or header
 * variables reads the body as a JSON tree and binds it after injecting the variables, outside Spring's decoder; its
 * binding failures answer exactly as the decoder's do on a route without variables.
 */
class CommandBodyBindingErrorTest {

    @CommandRoute(action = "members/{memberId}", method = CommandRoute.Method.POST)
    data class RemoveMember(
        @CommandRoute.PathVariable
        val memberId: String,
        val reason: String,
        val noticeDays: Int = 0,
    )

    private val withPathVariable = commandRouteMetadata<RemoveMember>()
    private val withoutVariables = withPathVariable.copy(pathVariableMetadata = emptySet())

    private fun client(commandRouteMetadata: CommandRouteMetadata<RemoveMember>): WebTestClient {
        val handlerFunction = CommandHandlerFunction(
            aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
            commandRouteMetadata = commandRouteMetadata,
            commandGateway = SagaVerifier.defaultCommandGateway(),
            commandMessageExtractor = CommandTestFixtures.MOCK_COMMAND_MESSAGE_EXTRACTOR,
            exceptionHandler = WebFluxRequestExceptionHandler(),
            commandWaitPolicy = CommandWaitPolicy(DEFAULT_TIME_OUT),
        )
        val routerFunction = RouterFunctions.route()
            .POST("/members/{memberId}", handlerFunction)
            .POST("/members", handlerFunction)
            .build()
        return WebTestClient.bindToRouterFunction(routerFunction)
            .handlerStrategies(HandlerStrategies.builder().exceptionHandler(DefaultGlobalExceptionHandler()).build())
            .build()
    }

    private fun WebTestClient.send(uri: String, body: String): WebTestClient.ResponseSpec =
        post().uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name)
            .bodyValue(body)
            .exchange()

    private fun WebTestClient.ResponseSpec.expectBadRequestBody(): String =
        expectStatus().isBadRequest
            .expectHeader().valueEquals(CommonComponent.Header.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    @Test
    fun `the route under test binds a path variable`() {
        withPathVariable.pathVariableMetadata.assert().isNotEmpty()
        withoutVariables.headerVariableMetadata.assert().isEmpty()
    }

    @Test
    fun `a missing required field is the same 400 as on a route without variables`() {
        val withPath = client(withPathVariable)
            .send("/members/member-1", """{"noticeDays":1}""")
            .expectBadRequestBody()
        val withoutPath = client(withoutVariables)
            .send("/members", """{"memberId":"member-1","noticeDays":1}""")
            .expectBadRequestBody()

        withPath.assert().contains("reason")
        withPath.assert().isEqualTo(withoutPath)
    }

    @Test
    fun `a field of the wrong type is a 400`() {
        val withPath = client(withPathVariable)
            .send("/members/member-1", """{"reason":"left","noticeDays":"soon"}""")
            .expectBadRequestBody()
        val withoutPath = client(withoutVariables)
            .send("/members", """{"memberId":"member-1","reason":"left","noticeDays":"soon"}""")
            .expectBadRequestBody()

        withPath.assert().isEqualTo(withoutPath)
    }

    @Test
    fun `a valid request is sent`() {
        client(withPathVariable)
            .send("/members/member-1", """{"reason":"left"}""")
            .expectStatus().isEqualTo(HttpStatus.OK)
    }
}
