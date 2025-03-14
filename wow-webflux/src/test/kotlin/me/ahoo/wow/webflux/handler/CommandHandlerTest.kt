package me.ahoo.wow.webflux.handler

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import me.ahoo.wow.openapi.route.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.route.command.CommandHandler
import me.ahoo.wow.webflux.route.command.DefaultCommandMessageParser
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.security.Principal
import java.time.Duration

class CommandHandlerTest {

    @Test
    fun handleSent() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns "SENT"
            every { headers().firstHeader(CommandRequestHeaders.WAIT_CONTEXT) } returns null
            every { headers().firstHeader(CommandRequestHeaders.WAIT_PROCESSOR) } returns "test"
            every { headers().firstHeader(CommandRequestHeaders.WAIT_TIME_OUT) } returns null
            every { pathVariables()[MessageRecords.TENANT_ID] } returns generateGlobalId()
            every { pathVariables()[MessageRecords.OWNER_ID] } returns generateGlobalId()
            every { pathVariables()[RoutePaths.ID_KEY] } returns generateGlobalId()
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_VERSION) } returns null
            every { headers().firstHeader(CommandRequestHeaders.REQUEST_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.LOCAL_FIRST) } returns true.toString()
            every { headers().accept().contains(MediaType.TEXT_EVENT_STREAM) } returns false
            every { principal() } returns mockk<Principal> {
                every { name } returns generateGlobalId()
            }.toMono()
            every { headers().asHttpHeaders() } returns HttpHeaders()
        }
        val commandHandler = CommandHandler(
            SagaVerifier.defaultCommandGateway(),
            DefaultCommandMessageParser(SimpleCommandMessageFactory(SimpleCommandBuilderRewriterRegistry()))
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
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns "PROCESSED"
            every { headers().firstHeader(CommandRequestHeaders.WAIT_CONTEXT) } returns "test"
            every { headers().firstHeader(CommandRequestHeaders.WAIT_PROCESSOR) } returns "test"
            every { headers().firstHeader(CommandRequestHeaders.WAIT_TIME_OUT) } returns 10.toString()
            every { pathVariables()[MessageRecords.TENANT_ID] } returns generateGlobalId()
            every { pathVariables()[MessageRecords.OWNER_ID] } returns generateGlobalId()
            every { pathVariables()[RoutePaths.ID_KEY] } returns null
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_VERSION) } returns null
            every { headers().firstHeader(CommandRequestHeaders.REQUEST_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.LOCAL_FIRST) } returns null
            every { headers().accept().contains(MediaType.TEXT_EVENT_STREAM) } returns false
            every { principal() } returns mockk<Principal> {
                every { name } returns generateGlobalId()
            }.toMono()
            every { headers().asHttpHeaders() } returns HttpHeaders()
        }
        val commandHandler = CommandHandler(
            SagaVerifier.defaultCommandGateway(),
            DefaultCommandMessageParser(SimpleCommandMessageFactory(SimpleCommandBuilderRewriterRegistry()))
        )
        commandHandler.handle(
            request,
            MockCreateAggregate(generateGlobalId(), generateGlobalId()),
            MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
        ).test()
            .verifyTimeout(Duration.ofMillis(110))
    }

    @Test
    fun handleEventStream() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns "PROCESSED"
            every { headers().firstHeader(CommandRequestHeaders.WAIT_CONTEXT) } returns "test"
            every { headers().firstHeader(CommandRequestHeaders.WAIT_PROCESSOR) } returns "test"
            every { headers().firstHeader(CommandRequestHeaders.WAIT_TIME_OUT) } returns 1000.toString()
            every { pathVariables()[MessageRecords.TENANT_ID] } returns generateGlobalId()
            every { pathVariables()[MessageRecords.OWNER_ID] } returns generateGlobalId()
            every { pathVariables()[RoutePaths.ID_KEY] } returns null
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_VERSION) } returns null
            every { headers().firstHeader(CommandRequestHeaders.REQUEST_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.LOCAL_FIRST) } returns null
            every { headers().accept().contains(MediaType.TEXT_EVENT_STREAM) } returns true
            every { principal() } returns mockk<Principal> {
                every { name } returns generateGlobalId()
            }.toMono()
            every { headers().asHttpHeaders() } returns HttpHeaders()
        }
        val commandHandler = CommandHandler(
            SagaVerifier.defaultCommandGateway(),
            DefaultCommandMessageParser(SimpleCommandMessageFactory(SimpleCommandBuilderRewriterRegistry()))
        )
        commandHandler.handle(
            request,
            MockCreateAggregate(generateGlobalId(), generateGlobalId()),
            MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
        ).test()
            .consumeNextWith {
                assertThat(it.stage, equalTo(CommandStage.SENT))
            }
            .verifyTimeout(Duration.ofMillis(110))
    }
}
