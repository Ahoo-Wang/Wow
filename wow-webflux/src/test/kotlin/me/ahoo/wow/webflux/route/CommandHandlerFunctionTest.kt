package me.ahoo.wow.webflux.route

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.route.commandRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.StatelessSagaVerifier
import me.ahoo.wow.webflux.exception.DefaultExceptionHandler
import me.ahoo.wow.webflux.route.appender.CommandHeaders
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.security.Principal

class CommandHandlerFunctionTest {

    @Test
    fun handle() {
        val commandGateway = spyk<CommandGateway>(StatelessSagaVerifier.defaultCommandGateway())
        val commandRouteMetadata = commandRouteMetadata<MockCreateAggregate>()
        val handlerFunction = CommandHandlerFunction(
            MOCK_AGGREGATE_METADATA,
            commandRouteMetadata,
            commandGateway,
            DefaultExceptionHandler
        )
        val request = mockk<ServerRequest> {
            every { bodyToMono(commandRouteMetadata.commandMetadata.commandType) } returns MockCreateAggregate(
                id = GlobalIdGenerator.generateAsString(),
                data = GlobalIdGenerator.generateAsString()
            ).toMono()
            every { headers().firstHeader(CommandHeaders.WAIT_TIME_OUT) } returns null
            every { pathVariables()[MessageRecords.TENANT_ID] } returns GlobalIdGenerator.generateAsString()
            every { headers().firstHeader(CommandHeaders.AGGREGATE_VERSION) } returns null
            every { headers().firstHeader(CommandHeaders.REQUEST_ID) } returns null
            every { principal() } returns mockk<Principal> {
                every { name } returns GlobalIdGenerator.generateAsString()
            }.toMono()
            every { headers().firstHeader(CommandHeaders.WAIT_STAGE) } returns CommandStage.SENT.toString()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
            }.verifyComplete()

        verify {
            commandGateway.sendAndWaitForSent<Any>(any())
        }
    }
}
