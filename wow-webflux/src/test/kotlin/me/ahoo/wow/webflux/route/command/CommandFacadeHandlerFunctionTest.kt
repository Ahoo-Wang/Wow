package me.ahoo.wow.webflux.route.command

import com.sun.security.auth.UserPrincipal
import io.mockk.spyk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.aggregate.command.CommandFacadeRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandMessageExtractor
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.util.function.Tuples

class CommandFacadeHandlerFunctionTest {

    @Test
    fun handle() {
        val commandGateway = spyk<CommandGateway>(SagaVerifier.defaultCommandGateway())

        val handlerFunction = CommandFacadeHandlerFunctionFactory(
            commandGateway = commandGateway,
            commandMessageExtractor = DefaultCommandMessageExtractor(
                SimpleCommandMessageFactory(
                    NoOpValidator,
                    SimpleCommandBuilderRewriterRegistry()
                ),
                DefaultCommandBuilderExtractor
            ),
            exceptionHandler = DefaultRequestExceptionHandler
        ).create(CommandFacadeRouteSpec(OpenAPIComponentContext.default()))
        val request = MockServerRequest.builder()
            .method(HttpMethod.POST)
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .principal(UserPrincipal(generateGlobalId()))
            .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.name)
            .header(CommandComponent.Header.COMMAND_TYPE, MockCreateAggregate::class.java.name)
            .body(
                Tuples.of(
                    MockCreateAggregate(
                        id = generateGlobalId(),
                        data = generateGlobalId(),
                    ) as Any,
                    MockCommandAggregate::class.java.aggregateRouteMetadata()
                ).toMono()
            )
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()

        verify {
            commandGateway.sendAndWait<Any>(any(), any())
        }
    }
}
