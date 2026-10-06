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

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.BuiltInHttpRoutePaths
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.DefaultGlobalExceptionHandler
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.command.extractor.routableCommandType
import me.ahoo.wow.webflux.route.policy.CommandWaitPolicy
import me.ahoo.wow.webflux.route.testGlobalRouteContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunctions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The global command facade accepts exactly the commands that have an aggregate command route. Any other
 * `Command-Type` answers like a disabled route (`404 NotFound`), and its name is never loaded as a class.
 */
class CommandFacadeAdmissionTest {
    private fun client(commandGateway: CommandGateway = mockk()): WebTestClient {
        val handlerFunction = CommandFacadeHandlerFunctionFactory(
            commandGateway = commandGateway,
            commandMessageExtractor = CommandTestFixtures.MOCK_COMMAND_MESSAGE_EXTRACTOR,
            exceptionHandler = WebFluxRequestExceptionHandler(),
            commandWaitPolicy = CommandWaitPolicy(DEFAULT_TIME_OUT),
        ).create(testGlobalRouteContract(BuiltInHttpRouteHandlerKeys.Global.COMMAND_FACADE))
        val routerFunction = RouterFunctions.route()
            .POST(BuiltInHttpRoutePaths.Global.COMMAND_SEND, handlerFunction)
            .build()
        return WebTestClient.bindToRouterFunction(routerFunction)
            .handlerStrategies(HandlerStrategies.builder().exceptionHandler(DefaultGlobalExceptionHandler()).build())
            .build()
    }

    private fun WebTestClient.send(
        commandType: String,
        body: String = "{}",
        aggregate: Pair<String, String>? = null
    ): WebTestClient.ResponseSpec {
        return post().uri(BuiltInHttpRoutePaths.Global.COMMAND_SEND)
            .contentType(MediaType.APPLICATION_JSON)
            .header(CommandComponent.Header.COMMAND_TYPE, commandType)
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name)
            .apply {
                if (aggregate != null) {
                    header(CommandComponent.Header.COMMAND_AGGREGATE_CONTEXT, aggregate.first)
                    header(CommandComponent.Header.COMMAND_AGGREGATE_NAME, aggregate.second)
                }
            }
            .bodyValue(body)
            .exchange()
    }

    private fun WebTestClient.ResponseSpec.expectNoCommandRoute() {
        expectStatus().isNotFound
            .expectHeader().valueEquals(CommonComponent.Header.ERROR_CODE, ErrorCodes.NOT_FOUND)
    }

    @Test
    fun `a registered command with an enabled route is accepted`() {
        val body = MockCreateAggregate(id = generateGlobalId(), data = generateGlobalId()).toJsonString()
        client(SagaVerifier.defaultCommandGateway())
            .send(MockCreateAggregate::class.java.name, body)
            .expectStatus().isOk
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"id":"mock-1"}""", """{"id":"mock-1","data":{"nested":true}}"""])
    fun `a body that does not bind to the command is a 400`(body: String) {
        client(SagaVerifier.defaultCommandGateway())
            .send(MockCreateAggregate::class.java.name, body)
            .expectStatus().isBadRequest
            .expectHeader().valueEquals(CommonComponent.Header.ERROR_CODE, ErrorCodes.ILLEGAL_ARGUMENT)
    }

    @Test
    fun `a class that is not a registered command is never loaded`() {
        val response = client().send(FacadeClassLoadProbe::class.java.name)

        LOADED.get().assert().isFalse()
        response.expectNoCommandRoute()
    }

    @ParameterizedTest
    @ValueSource(strings = ["java.lang.Runtime", "com.example.DoesNotExist", "", " "])
    fun `an unknown command type answers like a disabled route`(commandType: String) {
        client().send(commandType).expectNoCommandRoute()
    }

    @Test
    fun `a command of an aggregate whose routes are disabled answers like a disabled route`() {
        client().send(
            DefaultDeleteAggregate::class.java.name,
            aggregate = "example" to "disabled_route_aggregate"
        ).expectNoCommandRoute()
    }

    @Test
    fun `a registered command of another aggregate is not routable through this one`() {
        client().send(
            MockCreateAggregate::class.java.name,
            aggregate = "example" to "cart"
        ).expectNoCommandRoute()
    }

    @Test
    fun `only registered commands whose CommandRoute is enabled are routable`() {
        val routeMetadata = AggregateRouteMetadata(
            enabled = true,
            aggregateMetadata = MOCK_AGGREGATE_METADATA.copy(
                command = MOCK_AGGREGATE_METADATA.command.copy(
                    mountedCommands = setOf(EnabledFacadeCommand::class.java, DisabledFacadeCommand::class.java)
                )
            ),
            resourceName = "mock_aggregate",
            spaced = false,
            owner = AggregateRoute.Owner.NEVER
        )

        routeMetadata.routableCommandType(EnabledFacadeCommand::class.java.name)
            .assert().isEqualTo(EnabledFacadeCommand::class.java)
        routeMetadata.routableCommandType(DisabledFacadeCommand::class.java.name).assert().isNull()
        routeMetadata.routableCommandType(MockCreateAggregate::class.java.name)
            .assert().isEqualTo(MockCreateAggregate::class.java)
        routeMetadata.routableCommandType(DefaultDeleteAggregate::class.java.name)
            .assert().isEqualTo(DefaultDeleteAggregate::class.java)
        routeMetadata.copy(enabled = false).routableCommandType(MockCreateAggregate::class.java.name)
            .assert().isNull()
    }

    companion object {
        val LOADED = AtomicBoolean(false)
    }
}

/**
 * A class on the classpath that is no command: initializing it (as `Class.forName` does) flips
 * [CommandFacadeAdmissionTest.LOADED].
 */
class FacadeClassLoadProbe(val name: String) {
    companion object {
        init {
            CommandFacadeAdmissionTest.LOADED.set(true)
        }
    }
}

data class EnabledFacadeCommand(@AggregateId val id: String)

@CommandRoute(enabled = false)
data class DisabledFacadeCommand(@AggregateId val id: String)
