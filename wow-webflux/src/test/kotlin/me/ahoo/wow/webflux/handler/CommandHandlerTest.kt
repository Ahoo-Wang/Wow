package me.ahoo.wow.webflux.handler

import com.sun.security.auth.UserPrincipal
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import me.ahoo.wow.openapi.route.aggregateRouteMetadata
import me.ahoo.wow.serialization.toJsonString
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
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test
import java.time.Duration

class CommandHandlerTest {

    @Test
    fun handleSent() {
        val request = MockServerRequest.builder()
            .header(CommandRequestHeaders.WAIT_STAGE, CommandStage.SENT.name)
            .header(CommandRequestHeaders.WAIT_CONTEXT, "test")
            .header(CommandRequestHeaders.WAIT_PROCESSOR, "test")
            .header(CommandRequestHeaders.WAIT_TIME_OUT, "1000")
            .header(CommandRequestHeaders.TENANT_ID, generateGlobalId())
            .header(CommandRequestHeaders.OWNER_ID, generateGlobalId())
            .header(CommandRequestHeaders.LOCAL_FIRST, true.toString())
            .header(CommandRequestHeaders.AGGREGATE_ID, generateGlobalId())
            .header(CommandRequestHeaders.REQUEST_ID, generateGlobalId())
            .principal(UserPrincipal(generateGlobalId()))
            .body(MockCreateAggregate(generateGlobalId(), generateGlobalId()).toJsonString())
        val commandHandler = CommandHandler(
            SagaVerifier.defaultCommandGateway(),
            DefaultCommandMessageParser(
                SimpleCommandMessageFactory(
                    NoOpValidator,
                    SimpleCommandBuilderRewriterRegistry()
                )
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
            .header(CommandRequestHeaders.WAIT_STAGE, CommandStage.PROCESSED.name)
            .header(CommandRequestHeaders.WAIT_CONTEXT, "test")
            .header(CommandRequestHeaders.WAIT_PROCESSOR, "test")
            .header(CommandRequestHeaders.WAIT_TIME_OUT, "1000")
            .header(CommandRequestHeaders.TENANT_ID, generateGlobalId())
            .header(CommandRequestHeaders.OWNER_ID, generateGlobalId())
            .header(CommandRequestHeaders.LOCAL_FIRST, true.toString())
            .header(CommandRequestHeaders.AGGREGATE_ID, generateGlobalId())
            .header(CommandRequestHeaders.REQUEST_ID, generateGlobalId())
            .principal(UserPrincipal(generateGlobalId()))
            .body(MockCreateAggregate(generateGlobalId(), generateGlobalId()).toJsonString())
        val commandHandler = CommandHandler(
            SagaVerifier.defaultCommandGateway(),
            DefaultCommandMessageParser(
                SimpleCommandMessageFactory(
                    NoOpValidator,
                    SimpleCommandBuilderRewriterRegistry()
                )
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
            .header(CommandRequestHeaders.WAIT_STAGE, CommandStage.PROCESSED.name)
            .header(CommandRequestHeaders.WAIT_CONTEXT, "test")
            .header(CommandRequestHeaders.WAIT_PROCESSOR, "test")
            .header(CommandRequestHeaders.WAIT_TIME_OUT, "2000")
            .header(CommandRequestHeaders.TENANT_ID, generateGlobalId())
            .header(CommandRequestHeaders.OWNER_ID, generateGlobalId())
            .header(CommandRequestHeaders.LOCAL_FIRST, true.toString())
            .header(CommandRequestHeaders.AGGREGATE_ID, generateGlobalId())
            .header(CommandRequestHeaders.REQUEST_ID, generateGlobalId())
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM.toString())
            .principal(UserPrincipal(generateGlobalId()))
            .body(MockCreateAggregate(generateGlobalId(), generateGlobalId()).toJsonString())
        val commandHandler = CommandHandler(
            SagaVerifier.defaultCommandGateway(),
            DefaultCommandMessageParser(
                SimpleCommandMessageFactory(
                    NoOpValidator,
                    SimpleCommandBuilderRewriterRegistry()
                )
            )
        )
        commandHandler.handle(
            request,
            MockCreateAggregate(generateGlobalId(), generateGlobalId()),
            MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
        ).test()
            .consumeNextWith {
                assertThat(it.stage, equalTo(CommandStage.SENT))
            }
            .verifyTimeout(Duration.ofMillis(200))
    }
}
