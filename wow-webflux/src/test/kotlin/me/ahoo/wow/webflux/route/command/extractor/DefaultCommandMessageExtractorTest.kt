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

package me.ahoo.wow.webflux.route.command.extractor

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.webflux.route.command.appender.CommandRequestExtendHeaderAppender
import me.ahoo.wow.webflux.route.command.appender.CommandRequestHeaderAppender
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test
import java.security.Principal

class DefaultCommandMessageExtractorTest {

    @Test
    fun `should extract command message from request`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .pathVariable(CommandComponent.Header.AGGREGATE_VERSION, 1.toString())
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.toString())
            .header(CommandComponent.Header.LOCAL_FIRST, false.toString())
            .build()
        val commandMessageExtractor =
            DefaultCommandMessageExtractor(
                commandMessageFactory = SimpleCommandMessageFactory(
                    NoOpValidator,
                    SimpleCommandBuilderRewriterRegistry()
                ),
                commandBuilderExtractor = DefaultCommandBuilderExtractor
            )
        commandMessageExtractor.extract(
            aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
            commandBody = MockCreateAggregate(
                id = generateGlobalId(),
                data = generateGlobalId(),
            ),
            request
        ).test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `should inject extension headers into command message`() {
        val headerKey = "app"
        val key = CommandComponent.Header.COMMAND_HEADER_X_PREFIX + headerKey
        val value = "oms"

        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .pathVariable(CommandComponent.Header.AGGREGATE_VERSION, 1.toString())
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.toString())
            .header(CommandComponent.Header.LOCAL_FIRST, false.toString())
            .header(key, value)
            .build()
        val commandMessageExtractor =
            DefaultCommandMessageExtractor(
                commandMessageFactory = SimpleCommandMessageFactory(
                    validator = NoOpValidator,
                    commandBuilderRewriterRegistry = SimpleCommandBuilderRewriterRegistry()
                ),
                commandBuilderExtractor = DefaultCommandBuilderExtractor,
                commandRequestHeaderAppends = listOf(
                    CommandRequestExtendHeaderAppender
                )
            )
        commandMessageExtractor.extract(
            aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
            commandBody = MockCreateAggregate(
                id = generateGlobalId(),
                data = generateGlobalId(),
            ),
            request
        ).test()
            .consumeNextWith {
                it.header[headerKey].assert().isEqualTo(value)
            }
            .verifyComplete()
    }

    private fun extractor(vararg appenders: CommandRequestHeaderAppender) = DefaultCommandMessageExtractor(
        commandMessageFactory = SimpleCommandMessageFactory(
            validator = NoOpValidator,
            commandBuilderRewriterRegistry = SimpleCommandBuilderRewriterRegistry()
        ),
        commandBuilderExtractor = DefaultCommandBuilderExtractor,
        commandRequestHeaderAppends = appenders.toList()
    )

    private fun MockServerRequest.Builder.alice(): MockServerRequest.Builder =
        pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .principal(Principal { "alice" })

    private val routeMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
    private fun body() = MockCreateAggregate(id = generateGlobalId(), data = generateGlobalId())

    @Test
    fun `an operator spoofed through Command-Header is rejected`() {
        val request = MockServerRequest.builder().alice()
            .header("Command-Header-command_operator", "admin")
            .build()

        extractor(CommandRequestExtendHeaderAppender).extract(routeMetadata, body(), request)
            .test()
            .expectErrorMatches { it is IllegalArgumentException && it.message!!.contains("command_operator") }
            .verify()
    }

    @Test
    fun `a lower-case operator spoof, as HTTP 2 sends it, is rejected`() {
        val request = MockServerRequest.builder().alice()
            .header("command-header-command_operator", "admin")
            .build()

        extractor(CommandRequestExtendHeaderAppender).extract(routeMetadata, body(), request)
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `the authenticated operator is set after every appender`() {
        val overwriting = object : CommandRequestHeaderAppender {
            override fun append(request: ServerRequest, header: Header) {
                header.withOperator("mallory")
            }
        }
        val request = MockServerRequest.builder().alice().build()

        extractor(overwriting).extract(routeMetadata, body(), request)
            .test()
            .consumeNextWith {
                it.header.operator.assert().isEqualTo("alice")
            }
            .verifyComplete()
    }
}
