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

import com.sun.security.auth.UserPrincipal
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandMessageExtractor
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test
import java.time.Duration

class CommandHandlerTest {

    @Test
    fun handleSent() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name)
            .header(CommandComponent.Header.WAIT_CONTEXT, "test")
            .header(CommandComponent.Header.WAIT_PROCESSOR, "test")
            .header(CommandComponent.Header.WAIT_TIME_OUT, "1000")
            .header(CommandComponent.Header.TENANT_ID, generateGlobalId())
            .header(CommandComponent.Header.OWNER_ID, generateGlobalId())
            .header(CommandComponent.Header.LOCAL_FIRST, true.toString())
            .header(CommandComponent.Header.AGGREGATE_ID, generateGlobalId())
            .header(CommandComponent.Header.REQUEST_ID, generateGlobalId())
            .principal(UserPrincipal(generateGlobalId()))
            .body(MockCreateAggregate(generateGlobalId(), generateGlobalId()).toJsonString())
        val commandHandler = CommandHandler(
            commandGateway = SagaVerifier.defaultCommandGateway(),
            commandMessageExtractor = DefaultCommandMessageExtractor(
                commandMessageFactory = SimpleCommandMessageFactory(
                    validator = NoOpValidator,
                    commandBuilderRewriterRegistry = SimpleCommandBuilderRewriterRegistry()
                ),
                commandBuilderExtractor = DefaultCommandBuilderExtractor
            )
        )
        commandHandler.handle(
            request,
            MockCreateAggregate(generateGlobalId(), generateGlobalId()),
            MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
        ).test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun handleProcessed() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.PROCESSED.name)
            .header(CommandComponent.Header.WAIT_CONTEXT, "test")
            .header(CommandComponent.Header.WAIT_PROCESSOR, "test")
            .header(CommandComponent.Header.WAIT_TIME_OUT, "1000")
            .header(CommandComponent.Header.TENANT_ID, generateGlobalId())
            .header(CommandComponent.Header.OWNER_ID, generateGlobalId())
            .header(CommandComponent.Header.LOCAL_FIRST, true.toString())
            .header(CommandComponent.Header.AGGREGATE_ID, generateGlobalId())
            .header(CommandComponent.Header.REQUEST_ID, generateGlobalId())
            .principal(UserPrincipal(generateGlobalId()))
            .body(MockCreateAggregate(generateGlobalId(), generateGlobalId()).toJsonString())
        val commandHandler = CommandHandler(
            SagaVerifier.defaultCommandGateway(),
            DefaultCommandMessageExtractor(
                commandMessageFactory = SimpleCommandMessageFactory(
                    validator = NoOpValidator,
                    commandBuilderRewriterRegistry = SimpleCommandBuilderRewriterRegistry()
                ),
                commandBuilderExtractor = DefaultCommandBuilderExtractor
            )
        )
        commandHandler.handle(
            request,
            MockCreateAggregate(generateGlobalId(), generateGlobalId()),
            MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
        ).test()
            .verifyTimeout(Duration.ofMillis(200))
    }

    @Test
    fun handleEventStream() {
        val request = MockServerRequest.builder()
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.PROCESSED.name)
            .header(CommandComponent.Header.WAIT_CONTEXT, "test")
            .header(CommandComponent.Header.WAIT_PROCESSOR, "test")
            .header(CommandComponent.Header.WAIT_TIME_OUT, "2000")
            .header(CommandComponent.Header.TENANT_ID, generateGlobalId())
            .header(CommandComponent.Header.OWNER_ID, generateGlobalId())
            .header(CommandComponent.Header.LOCAL_FIRST, true.toString())
            .header(CommandComponent.Header.AGGREGATE_ID, generateGlobalId())
            .header(CommandComponent.Header.REQUEST_ID, generateGlobalId())
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM.toString())
            .principal(UserPrincipal(generateGlobalId()))
            .body(MockCreateAggregate(generateGlobalId(), generateGlobalId()).toJsonString())
        val commandHandler = CommandHandler(
            commandGateway = SagaVerifier.defaultCommandGateway(),
            commandMessageExtractor = DefaultCommandMessageExtractor(
                commandMessageFactory = SimpleCommandMessageFactory(
                    NoOpValidator,
                    SimpleCommandBuilderRewriterRegistry()
                ),
                commandBuilderExtractor = DefaultCommandBuilderExtractor
            )
        )
        commandHandler.handle(
            request,
            MockCreateAggregate(generateGlobalId(), generateGlobalId()),
            MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
        ).test()
            .consumeNextWith {
                it.stage.assert().isEqualTo(CommandStage.SENT)
            }
            .verifyTimeout(Duration.ofMillis(200))
    }
}
