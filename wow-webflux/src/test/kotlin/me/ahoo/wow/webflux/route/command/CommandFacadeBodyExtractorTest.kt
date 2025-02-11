package me.ahoo.wow.webflux.route.command

import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import me.ahoo.wow.openapi.route.aggregateRouteMetadata
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
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
        httpHeaders.set(CommandRequestHeaders.COMMAND_TYPE, MockCreateAggregate::class.java.name)
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
                assertThat(it.t1, isA(MockCreateAggregate::class.java))
                assertThat(
                    it.t2,
                    equalTo(
                        aggregateMetadata<MockCommandAggregate, MockStateAggregate>().command.aggregateType.aggregateRouteMetadata()
                    )
                )
            }
            .verifyComplete()
    }

    @Test
    fun extractIfEmtpy() {
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
        httpHeaders.set(CommandRequestHeaders.COMMAND_AGGREGATE_CONTEXT, aggregateMetadata.contextName)
        httpHeaders.set(CommandRequestHeaders.COMMAND_AGGREGATE_NAME, aggregateMetadata.aggregateName)
        httpHeaders.set(CommandRequestHeaders.COMMAND_TYPE, DefaultDeleteAggregate::class.java.name)
        val inputMessage = mockk<ReactiveHttpInputMessage> {
            every { headers } returns httpHeaders
            every { body } returns Flux.empty()
        }
        commandBodyExtractor.extract(inputMessage, context)
            .test()
            .consumeNextWith {
                assertThat(it.t1, isA(DefaultDeleteAggregate::class.java))
                assertThat(it.t2, equalTo(aggregateMetadata.command.aggregateType.aggregateRouteMetadata()))
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
        httpHeaders.set(CommandRequestHeaders.COMMAND_TYPE, MockCreateAggregate::class.java.name)
        httpHeaders.set(
            CommandRequestHeaders.COMMAND_AGGREGATE_CONTEXT,
            MockCreateAggregate::class.java.requiredNamedAggregate().contextName
        )
        httpHeaders.set(
            CommandRequestHeaders.COMMAND_AGGREGATE_NAME,
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
                assertThat(it.t1, isA(MockCreateAggregate::class.java))
                assertThat(
                    it.t2,
                    equalTo(
                        aggregateMetadata<MockCommandAggregate, MockStateAggregate>().command.aggregateType.aggregateRouteMetadata()
                    )
                )
            }
            .verifyComplete()
    }
}
