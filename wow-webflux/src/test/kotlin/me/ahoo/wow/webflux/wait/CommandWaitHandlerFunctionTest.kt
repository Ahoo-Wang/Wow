package me.ahoo.wow.webflux.wait

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.command.COMMAND_GATEWAY_FUNCTION
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class CommandWaitHandlerFunctionTest {

    @Test
    fun handle() {
        val commandWaitHandlerFunction = CommandWaitHandlerFunction(SimpleWaitStrategyRegistrar)
        val request = mockk<ServerRequest> {
            every { bodyToMono(SimpleWaitSignal::class.java) } returns SimpleWaitSignal(
                commandId = "commandId",
                stage = CommandStage.SENT,
                function = COMMAND_GATEWAY_FUNCTION,
            ).toMono()
        }
        val response = commandWaitHandlerFunction.handle(request)
        response.test()
            .consumeNextWith {
                assertThat(it.statusCode().is2xxSuccessful, equalTo(true))
            }
            .verifyComplete()
    }
}
