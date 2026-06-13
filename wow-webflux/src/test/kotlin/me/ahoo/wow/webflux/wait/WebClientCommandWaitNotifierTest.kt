/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.webflux.wait

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.WaitCoordinator
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
    fun `should notify local wait signal`() {
        val webClient = mockk<WebClient>()
        val waitCoordinator = mockk<WaitCoordinator> {
            every { signal(any()) } returns true
        }
        val commandWaitNotifier = WebClientCommandWaitNotifier(waitCoordinator, webClient)
        val commandWaitEndpoint = "http://localhost:8080/command/wait"
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = generateGlobalId(),
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
        verify { waitCoordinator.signal(waitSignal) }
    }

    @Test
    fun `should notify remote wait signal`() {
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
        val waitCoordinator = mockk<WaitCoordinator>(relaxed = true)
        val commandWaitNotifier = WebClientCommandWaitNotifier(waitCoordinator, webClient)

        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = "0ToC0Bez003X00Z",
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
        verify(exactly = 0) { waitCoordinator.signal(any()) }
    }
}
