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
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.webflux.route.command.appender.CommandRequestExtendHeaderAppender
import org.hamcrest.CoreMatchers
import org.hamcrest.MatcherAssert
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class DefaultCommandMessageParserTest {

    @Test
    fun parse() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_TIME_OUT) } returns null
            every { pathVariables()[MessageRecords.TENANT_ID] } returns generateGlobalId()
            every { pathVariables()[MessageRecords.OWNER_ID] } returns generateGlobalId()
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_VERSION) } returns 1.toString()
            every { pathVariables()[RoutePaths.ID_KEY] } returns null
            every { headers().firstHeader(CommandRequestHeaders.TENANT_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.OWNER_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.REQUEST_ID) } returns null
            every { principal() } returns Mono.empty()
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns CommandStage.SENT.toString()
            every { headers().firstHeader(CommandRequestHeaders.LOCAL_FIRST) } returns false.toString()
        }
        val commandMessageParser =
            DefaultCommandMessageParser(SimpleCommandMessageFactory((SimpleCommandBuilderRewriterRegistry())))
        commandMessageParser.parse(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
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
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_TIME_OUT) } returns null
            every { pathVariables()[MessageRecords.TENANT_ID] } returns generateGlobalId()
            every { pathVariables()[MessageRecords.OWNER_ID] } returns generateGlobalId()
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_VERSION) } returns 1.toString()
            every { pathVariables()[RoutePaths.ID_KEY] } returns null
            every { headers().firstHeader(CommandRequestHeaders.TENANT_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.REQUEST_ID) } returns null
            every { principal() } returns Mono.empty()
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns CommandStage.SENT.toString()
            every { headers().firstHeader(CommandRequestHeaders.LOCAL_FIRST) } returns false.toString()
            every { headers().asHttpHeaders() } returns HttpHeaders(
                MultiValueMap.fromSingleValue<String, String>(
                    mapOf(
                        key to value
                    )
                )
            )
        }
        val commandMessageParser =
            DefaultCommandMessageParser(
                SimpleCommandMessageFactory((SimpleCommandBuilderRewriterRegistry())),
                listOf(
                    CommandRequestExtendHeaderAppender
                )
            )
        commandMessageParser.parse(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
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
