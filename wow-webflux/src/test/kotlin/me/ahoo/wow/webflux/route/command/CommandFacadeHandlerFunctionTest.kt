package me.ahoo.wow.webflux.route.command

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.util.function.Tuples
import java.net.URI
import java.security.Principal

class CommandFacadeHandlerFunctionTest {

    @Suppress("UNCHECKED_CAST")
    @Test
    fun handle() {
        val commandGateway = spyk<CommandGateway>(SagaVerifier.defaultCommandGateway())
        val handlerFunction = CommandFacadeHandlerFunction(
            commandGateway,
            DefaultCommandMessageParser(
                SimpleCommandMessageFactory(
                    NoOpValidator,
                    SimpleCommandBuilderRewriterRegistry()
                )
            ),
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
            every { method() } returns HttpMethod.POST
            every { uri() } returns URI.create("http://localhost:8080")
            every { headers().firstHeader(CommandComponent.Header.WAIT_TIME_OUT) } returns null
            every { pathVariables()[MessageRecords.TENANT_ID] } returns generateGlobalId()
            every { pathVariables()[MessageRecords.OWNER_ID] } returns null
            every { headers().firstHeader(CommandComponent.Header.AGGREGATE_VERSION) } returns null
            every { pathVariables()[MessageRecords.ID] } returns null
            every { headers().firstHeader(CommandComponent.Header.AGGREGATE_ID) } returns null
            every { headers().firstHeader(CommandComponent.Header.OWNER_ID) } returns null
            every { headers().firstHeader(CommandComponent.Header.REQUEST_ID) } returns null
            every { headers().firstHeader(CommandComponent.Header.LOCAL_FIRST) } returns true.toString()
            every { headers().firstHeader(CommandComponent.Header.WAIT_CONTEXT) } returns null
            every { headers().firstHeader(CommandComponent.Header.WAIT_PROCESSOR) } returns null
            every { headers().accept().contains(MediaType.TEXT_EVENT_STREAM) } returns false
            every { headers().firstHeader(CommandComponent.Header.COMMAND_TYPE) } returns MockCreateAggregate::class.java.name
            every { principal() } returns mockk<Principal> {
                every { name } returns generateGlobalId()
            }.toMono()
            every { headers().firstHeader(CommandComponent.Header.WAIT_STAGE) } returns CommandStage.SENT.toString()
            every { headers().asHttpHeaders() } returns HttpHeaders()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
            }.verifyComplete()

        verify {
            commandGateway.sendAndWait<Any>(any(), any())
        }
    }
}
