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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.serialization.JsonSerializer
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

class CommandBodyExtractorTest {

    @Test
    fun extract() {
        val commandRouteMetadata = commandRouteMetadata<DefaultDeleteAggregate>()
        val commandBodyExtractor = CommandBodyExtractor(commandRouteMetadata)
        val messageReader = mockk<HttpMessageReader<*>> {
            every { canRead(any(), any()) } returns true
            every { readMono(any(), any(), any()) } returns Mono.just(JsonSerializer.createObjectNode())
        }
        val context = mockk<BodyExtractor.Context> {
            every { hints()[RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE] } returns emptyMap<String, String>()
            every { messageReaders() } returns listOf(messageReader)
            every { serverResponse() } returns Optional.empty()
        }
        val httpHeaders = HttpHeaders()
        httpHeaders.contentType = MediaType.APPLICATION_JSON

        val inputMessage = mockk<ReactiveHttpInputMessage> {
            every { headers } returns httpHeaders
            every { body } returns Flux.just(DefaultDataBufferFactory.sharedInstance.wrap("{}".toByteArray()))
        }
        commandBodyExtractor.extract(inputMessage, context)
            .test()
            .consumeNextWith {
                it.assert().isInstanceOf(DefaultDeleteAggregate::class.java)
            }
            .verifyComplete()
    }

    @Test
    fun extractIfEmpty() {
        val commandRouteMetadata = commandRouteMetadata<DefaultDeleteAggregate>()
        val commandBodyExtractor = CommandBodyExtractor(commandRouteMetadata)
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

        val inputMessage = mockk<ReactiveHttpInputMessage> {
            every { headers } returns httpHeaders
            every { body } returns Flux.empty()
        }
        commandBodyExtractor.extract(inputMessage, context)
            .test()
            .consumeNextWith {
                it.assert().isInstanceOf(DefaultDeleteAggregate::class.java)
            }
            .verifyComplete()
    }
}
