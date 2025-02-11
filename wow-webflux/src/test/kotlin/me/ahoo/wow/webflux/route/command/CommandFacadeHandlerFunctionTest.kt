package me.ahoo.wow.webflux.route.command

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import me.ahoo.wow.openapi.route.AggregateRouteMetadata
import me.ahoo.wow.openapi.route.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.util.function.Tuples
import java.security.Principal

class CommandFacadeHandlerFunctionTest {

    @Suppress("UNCHECKED_CAST")
    @Test
    fun handle() {
        val commandGateway = spyk<CommandGateway>(SagaVerifier.defaultCommandGateway())
        val handlerFunction = CommandFacadeHandlerFunction(
            commandGateway,
            DefaultCommandMessageParser(SimpleCommandMessageFactory((SimpleCommandBuilderRewriterRegistry()))),
            DefaultRequestExceptionHandler
        )

        val request = mockk<ServerRequest> {
            every { body(CommandFacadeBodyExtractor) } returns Tuples.of(
                MockCreateAggregate(
                    id = generateGlobalId(),
                    data = generateGlobalId(),
                ) as Any,
                MockCommandAggregate::class.java.aggregateRouteMetadata() as AggregateRouteMetadata<Any>
            ).toMono()
            every { headers().firstHeader(CommandRequestHeaders.WAIT_TIME_OUT) } returns null
            every { pathVariables()[MessageRecords.TENANT_ID] } returns generateGlobalId()
            every { pathVariables()[MessageRecords.OWNER_ID] } returns null
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_VERSION) } returns null
            every { pathVariables()[RoutePaths.ID_KEY] } returns null
            every { headers().firstHeader(CommandRequestHeaders.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.OWNER_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.REQUEST_ID) } returns null
            every { headers().firstHeader(CommandRequestHeaders.LOCAL_FIRST) } returns true.toString()
            every { headers().firstHeader(CommandRequestHeaders.COMMAND_TYPE) } returns MockCreateAggregate::class.java.name
            every { principal() } returns mockk<Principal> {
                every { name } returns generateGlobalId()
            }.toMono()
            every { headers().firstHeader(CommandRequestHeaders.WAIT_STAGE) } returns CommandStage.SENT.toString()
            every { headers().asHttpHeaders() } returns HttpHeaders()
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
