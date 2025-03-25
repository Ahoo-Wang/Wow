package me.ahoo.wow.webflux.handler

import com.sun.security.auth.UserPrincipal
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
