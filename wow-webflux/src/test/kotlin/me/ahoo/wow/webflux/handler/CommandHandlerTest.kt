package me.ahoo.wow.webflux.handler

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.route.appender.CommandHeaders
import me.ahoo.wow.webflux.route.appender.RoutePaths
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.security.Principal
import java.time.Duration

class CommandHandlerTest {

    @Test
    fun handleSent() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandHeaders.WAIT_STAGE) } returns "SENT"
            every { headers().firstHeader(CommandHeaders.WAIT_CONTEXT) } returns "test"
            every { headers().firstHeader(CommandHeaders.WAIT_PROCESSOR) } returns "test"
            every { headers().firstHeader(CommandHeaders.WAIT_TIME_OUT) } returns null
            every { pathVariables()[MessageRecords.TENANT_ID] } returns GlobalIdGenerator.generateAsString()
            every { pathVariables()[RoutePaths.ID_KEY] } returns GlobalIdGenerator.generateAsString()
            every { headers().firstHeader(CommandHeaders.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandHeaders.AGGREGATE_VERSION) } returns null
            every { headers().firstHeader(CommandHeaders.REQUEST_ID) } returns null
            every { principal() } returns mockk<Principal> {
                every { name } returns GlobalIdGenerator.generateAsString()
            }.toMono()
        }
        val commandHandler = CommandHandler(MOCK_AGGREGATE_METADATA, SagaVerifier.defaultCommandGateway())
        commandHandler.handle(
            request,
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
        ).test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun handleProcessed() {
        val request = mockk<ServerRequest> {
            every { headers().firstHeader(CommandHeaders.WAIT_STAGE) } returns "PROCESSED"
            every { headers().firstHeader(CommandHeaders.WAIT_CONTEXT) } returns "test"
            every { headers().firstHeader(CommandHeaders.WAIT_PROCESSOR) } returns "test"
            every { headers().firstHeader(CommandHeaders.WAIT_TIME_OUT) } returns 10.toString()
            every { pathVariables()[MessageRecords.TENANT_ID] } returns GlobalIdGenerator.generateAsString()
            every { pathVariables()[RoutePaths.ID_KEY] } returns null
            every { headers().firstHeader(CommandHeaders.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandHeaders.AGGREGATE_VERSION) } returns null
            every { headers().firstHeader(CommandHeaders.REQUEST_ID) } returns null
            every { principal() } returns mockk<Principal> {
                every { name } returns GlobalIdGenerator.generateAsString()
            }.toMono()
        }
        val commandHandler = CommandHandler(MOCK_AGGREGATE_METADATA, SagaVerifier.defaultCommandGateway())
        commandHandler.handle(
            request,
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
        ).test()
            .verifyTimeout(Duration.ofMillis(110))
    }
}
