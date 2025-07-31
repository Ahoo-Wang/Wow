package me.ahoo.wow.webflux.wait

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
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
                id = generateGlobalId(),
                commandId = "commandId",
                aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
                stage = CommandStage.SENT,
                function = FunctionInfoData(
                    functionKind = FunctionKind.COMMAND,
                    contextName = "contextName",
                    processorName = "processorName",
                    name = "name"
                ),
            ).toMono()
        }
        val response = commandWaitHandlerFunction.handle(request)
        response.test()
            .consumeNextWith {
                it.statusCode().is2xxSuccessful.assert().isTrue()
            }
            .verifyComplete()
    }
}
