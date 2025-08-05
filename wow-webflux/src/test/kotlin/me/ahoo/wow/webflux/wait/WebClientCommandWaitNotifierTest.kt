package me.ahoo.wow.webflux.wait

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class WebClientCommandWaitNotifierTest {

    @Test
    fun notifyLocal() {
        val webClient = mockk<WebClient>()
        val commandWaitNotifier = WebClientCommandWaitNotifier(SimpleWaitStrategyRegistrar, webClient)
        val commandWaitEndpoint = "http://localhost:8080/command/wait"
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            commandWaitId = generateGlobalId(),
            commandId = GlobalIdGenerator.generateAsString(),
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.SENT,
            function = FunctionInfoData(
                functionKind = FunctionKind.COMMAND,
                contextName = "contextName",
                processorName = "processorName",
                name = "name"
            ),
        )
        commandWaitNotifier.notify(commandWaitEndpoint, waitSignal)
            .test()
            .verifyComplete()
    }

    @Test
    fun notifyRemote() {
        val commandWaitEndpoint = "http://localhost:8080/command/wait"
        val webClient = mockk<WebClient> {
            every {
                post()
                    .uri(commandWaitEndpoint)
                    .contentType(any())
                    .bodyValue(any())
                    .retrieve()
                    .bodyToMono(Void::class.java)
                    .retryWhen(any())
            } returns Mono.empty()
        }
        val commandWaitNotifier = WebClientCommandWaitNotifier(SimpleWaitStrategyRegistrar, webClient)

        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            commandWaitId = "0ToC0Bez003X00Z",
            commandId = "0ToC0Bez003X00Z",
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(),
            stage = CommandStage.SENT,
            function = FunctionInfoData(
                functionKind = FunctionKind.COMMAND,
                contextName = "contextName",
                processorName = "processorName",
                name = "name"
            ),
        )
        commandWaitNotifier.notify(commandWaitEndpoint, waitSignal)
            .test()
            .verifyComplete()
    }
}
