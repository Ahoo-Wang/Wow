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

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import me.ahoo.wow.openapi.route.aggregateRouteMetadata
import me.ahoo.wow.openapi.route.commandRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.net.URI
import java.security.Principal

class CommandHandlerFunctionTest {

    @Test
    fun handle() {
        val commandGateway = spyk<CommandGateway>(SagaVerifier.defaultCommandGateway())
        val commandRouteMetadata = commandRouteMetadata<MockCreateAggregate>()
        val handlerFunction = CommandHandlerFunction(
            MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
            commandRouteMetadata,
            commandGateway,
            DefaultCommandMessageParser(SimpleCommandMessageFactory((SimpleCommandBuilderRewriterRegistry()))),
            DefaultRequestExceptionHandler,
        )
        val request = mockk<ServerRequest> {
            every { bodyToMono(commandRouteMetadata.commandMetadata.commandType) } returns MockCreateAggregate(
                id = generateGlobalId(),
                data = generateGlobalId(),
            ).toMono()
            every { method() } returns HttpMethod.POST
            every { uri() } returns URI.create("http://localhost:8080")
            every { headers().firstHeader(CommandRequestHeaders.WAIT_TIME_OUT) } returns null
            every { pathVariables()[MessageRecords.TENANT_ID] } returns generateGlobalId()
            every { pathVariables()[MessageRecords.OWNER_ID] } returns generateGlobalId()
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_VERSION) } returns null
            every { pathVariables()[RoutePaths.ID_KEY] } returns null
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.REQUEST_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.LOCAL_FIRST) } returns null
            every { headers().firstHeader(CommandRequestHeaders.WAIT_CONTEXT) } returns null
            every { headers().firstHeader(CommandRequestHeaders.WAIT_PROCESSOR) } returns null
            every { headers().accept().contains(MediaType.TEXT_EVENT_STREAM) } returns false
            every { principal() } returns mockk<Principal> {
                every { name } returns generateGlobalId()
            }.toMono()
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns CommandStage.SENT.toString()
            every { headers().asHttpHeaders() } returns HttpHeaders()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
            }.verifyComplete()

        verify {
            commandGateway.sendAndWait<Any>(any(), any())
        }
    }
}
