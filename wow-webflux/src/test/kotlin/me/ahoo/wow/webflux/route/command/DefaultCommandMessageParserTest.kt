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

import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import me.ahoo.wow.openapi.route.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.webflux.route.command.appender.CommandRequestExtendHeaderAppender
import org.hamcrest.CoreMatchers
import org.hamcrest.MatcherAssert
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

class DefaultCommandMessageParserTest {

    @Test
    fun parse() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .pathVariable(CommandRequestHeaders.AGGREGATE_VERSION, 1.toString())
            .header(CommandRequestHeaders.WAIT_STAGE, CommandStage.SENT.toString())
            .header(CommandRequestHeaders.LOCAL_FIRST, false.toString())
            .build()
        val commandMessageParser =
            DefaultCommandMessageParser(SimpleCommandMessageFactory((SimpleCommandBuilderRewriterRegistry())))
        commandMessageParser.parse(
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
    fun injectExtensionHeaders() {
        val headerKey = "app"
        val key = CommandRequestHeaders.COMMAND_HEADER_X_PREFIX + headerKey
        val value = "oms"

        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .pathVariable(CommandRequestHeaders.AGGREGATE_VERSION, 1.toString())
            .header(CommandRequestHeaders.WAIT_STAGE, CommandStage.SENT.toString())
            .header(CommandRequestHeaders.LOCAL_FIRST, false.toString())
            .header(key, value)
            .build()
        val commandMessageParser =
            DefaultCommandMessageParser(
                SimpleCommandMessageFactory((SimpleCommandBuilderRewriterRegistry())),
                listOf(
                    CommandRequestExtendHeaderAppender
                )
            )
        commandMessageParser.parse(
            aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
            commandBody = MockCreateAggregate(
                id = generateGlobalId(),
                data = generateGlobalId(),
            ),
            request
        ).test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.header[headerKey], CoreMatchers.equalTo(value))
            }
            .verifyComplete()
    }
}
