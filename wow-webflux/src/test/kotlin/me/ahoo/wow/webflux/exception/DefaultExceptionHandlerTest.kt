package me.ahoo.wow.webflux.exception

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.CommandResultException
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test
import java.net.URI

class DefaultExceptionHandlerTest {

    @Test
    fun handle() {
        val request = MockServerRequest.builder()
            .method(HttpMethod.GET)
            .uri(URI.create("http://localhost"))
            .build()
        DefaultRequestExceptionHandler.handle(request, IllegalArgumentException("error"))
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.BAD_REQUEST)
            }
            .verifyComplete()
    }

    @Test
    fun handleCommandResultException() {
        val request = MockServerRequest.builder()
            .method(HttpMethod.GET)
            .uri(URI.create("http://localhost"))
            .build()
        val commandResult = CommandResult(
            id = generateGlobalId(),
            stage = CommandStage.SENT,
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            requestId = generateGlobalId(),
            commandId = generateGlobalId(),
            contextName = "contextName",
            processorName = "processorName",
            errorCode = ErrorCodes.NOT_FOUND
        )
        DefaultRequestExceptionHandler.handle(request, CommandResultException(commandResult))
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.NOT_FOUND)
            }
            .verifyComplete()
    }
}
