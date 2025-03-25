package me.ahoo.wow.webflux.route.command

import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.serialization.JsonSerializer
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
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
            every { readMono(any(), any(), any()) } returns Mono.just(ObjectNode(JsonSerializer.nodeFactory))
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
                assertThat(it, isA(DefaultDeleteAggregate::class.java))
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
                assertThat(it, isA(DefaultDeleteAggregate::class.java))
            }
            .verifyComplete()
    }
}
