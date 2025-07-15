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

import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ReactiveHttpInputMessage
import org.springframework.http.codec.HttpMessageReader
import org.springframework.web.reactive.function.BodyExtractor
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.*

class CommandFacadeBodyExtractorTest {

    @Test
    fun extract() {
        val command = MockCreateAggregate("id", "data")
        val commandBodyExtractor = CommandFacadeBodyExtractor
        val messageReader = mockk<HttpMessageReader<*>> {
            every { canRead(any(), any()) } returns true
            every { readMono(any(), any(), any()) } returns Mono.just(command.toJsonString().toObject<ObjectNode>())
        }
        val context = mockk<BodyExtractor.Context> {
            every { hints()[RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE] } returns emptyMap<String, String>()
            every { messageReaders() } returns listOf(messageReader)
            every { serverResponse() } returns Optional.empty()
        }

        val httpHeaders = HttpHeaders()
        httpHeaders.contentType = MediaType.APPLICATION_JSON
        httpHeaders.set(CommandComponent.Header.COMMAND_TYPE, MockCreateAggregate::class.java.name)
        val inputMessage = mockk<ReactiveHttpInputMessage> {
            every { headers } returns httpHeaders
            every { body } returns Flux.just(
                DefaultDataBufferFactory.sharedInstance.wrap(
                    command.toJsonString().toByteArray()
                )
            )
        }
        commandBodyExtractor.extract(inputMessage, context)
            .test()
            .consumeNextWith {
                it.t1.assert().isInstanceOf(MockCreateAggregate::class.java)
                it.t2.assert().isEqualTo(
                    aggregateMetadata<MockCommandAggregate, MockStateAggregate>().command.aggregateType.aggregateRouteMetadata()
                )
            }
            .verifyComplete()
    }

    @Test
    fun extractIfEmpty() {
        val aggregateMetadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        val commandBodyExtractor = CommandFacadeBodyExtractor
        val messageReader = mockk<HttpMessageReader<*>> {
            every { canRead(any(), any()) } returns true
            every { readMono(any(), any(), any()) } returns Mono.empty()
        }
        val context = mockk<BodyExtractor.Context> {
            every { hints()[RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE] } returns emptyMap<String, String>()
            every { messageReaders() } returns listOf(messageReader)
            every { serverResponse() } returns Optional.empty()
        }

        val httpHeaders = HttpHeaders()
        httpHeaders.contentType = MediaType.APPLICATION_JSON
        httpHeaders.set(CommandComponent.Header.COMMAND_AGGREGATE_CONTEXT, aggregateMetadata.contextName)
        httpHeaders.set(CommandComponent.Header.COMMAND_AGGREGATE_NAME, aggregateMetadata.aggregateName)
        httpHeaders.set(CommandComponent.Header.COMMAND_TYPE, DefaultDeleteAggregate::class.java.name)
        val inputMessage = mockk<ReactiveHttpInputMessage> {
            every { headers } returns httpHeaders
            every { body } returns Flux.empty()
        }
        commandBodyExtractor.extract(inputMessage, context)
            .test()
            .consumeNextWith {
                it.t1.assert().isInstanceOf(DefaultDeleteAggregate::class.java)
                it.t2.assert().isEqualTo(aggregateMetadata.command.aggregateType.aggregateRouteMetadata())
            }
            .verifyComplete()
    }

    @Test
    fun extractIfSetAggregate() {
        val command = MockCreateAggregate("id", "data")
        val commandBodyExtractor = CommandFacadeBodyExtractor
        val messageReader = mockk<HttpMessageReader<*>> {
            every { canRead(any(), any()) } returns true
            every { readMono(any(), any(), any()) } returns Mono.just(command.toJsonString().toObject<ObjectNode>())
        }
        val context = mockk<BodyExtractor.Context> {
            every { hints()[RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE] } returns emptyMap<String, String>()
            every { messageReaders() } returns listOf(messageReader)
            every { serverResponse() } returns Optional.empty()
        }

        val httpHeaders = HttpHeaders()
        httpHeaders.contentType = MediaType.APPLICATION_JSON
        httpHeaders.set(CommandComponent.Header.COMMAND_TYPE, MockCreateAggregate::class.java.name)
        httpHeaders.set(
            CommandComponent.Header.COMMAND_AGGREGATE_CONTEXT,
            MockCreateAggregate::class.java.requiredNamedAggregate().contextName
        )
        httpHeaders.set(
            CommandComponent.Header.COMMAND_AGGREGATE_NAME,
            MockCreateAggregate::class.java.requiredNamedAggregate().aggregateName
        )
        val inputMessage = mockk<ReactiveHttpInputMessage> {
            every { headers } returns httpHeaders
            every { body } returns Flux.just(
                DefaultDataBufferFactory.sharedInstance.wrap(
                    command.toJsonString().toByteArray()
                )
            )
        }
        commandBodyExtractor.extract(inputMessage, context)
            .test()
            .consumeNextWith {
                it.t1.assert().isInstanceOf(MockCreateAggregate::class.java)
                it.t2.assert().isEqualTo(
                    aggregateMetadata<MockCommandAggregate, MockStateAggregate>().command.aggregateType.aggregateRouteMetadata()
                )
            }
            .verifyComplete()
    }
}
