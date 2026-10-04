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

package me.ahoo.wow.spring.boot.starter.webflux

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.serialization.SerializationAutoConfiguration
import me.ahoo.wow.tck.wire.WireGolden
import me.ahoo.wow.tck.wire.WireSamples
import me.ahoo.wow.webflux.wait.CommandWaitHandlerFunction
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.http.codec.CodecCustomizer
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.webclient.WebClientCustomizer
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.ClientHttpRequest
import org.springframework.http.client.reactive.ClientHttpResponse
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.mock.http.client.reactive.MockClientHttpResponse
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.net.URI
import java.util.function.Function

/**
 * Golden samples of the `WaitSignal` JSON a node posts to another node's command wait endpoint
 * (`WebClientCommandWaitNotifier` → `CommandWaitHandlerFunction`).
 *
 * The body is produced by the Spring Boot `WebClient` codecs (Boot's Jackson mapper with the Wow module), not by the
 * Wow `JsonSerializer`, so the golden is taken through the auto-configured notifier and decoded through the handler
 * function with the auto-configured codecs.
 *
 * The files under `src/test/resources/wire/v9/` were generated from 9.2.2 and are **frozen v9 wire contracts**: the
 * node that waits and the node that processes can run different 9.x versions during a rolling upgrade. Changing a
 * golden needs a design decision, not a re-generation.
 */
class WaitSignalWireFormatGoldenTest {

    @Test
    fun `processed wait signal keeps the v9 wire format`() {
        verify(WireSamples.processedWaitSignal(), "wait-signal-processed.json")
    }

    @Test
    fun `failed wait signal keeps the v9 wire format`() {
        verify(WireSamples.failedWaitSignal(), "wait-signal-failed.json")
    }

    private fun verify(signal: WaitSignal, golden: String) {
        val connector = CapturingConnector()
        ApplicationContextRunner()
            .enableWow()
            .withConfiguration(
                AutoConfigurations.of(
                    SerializationAutoConfiguration::class.java,
                    JacksonAutoConfiguration::class.java,
                    CodecsAutoConfiguration::class.java,
                    WebClientAutoConfiguration::class.java,
                    WowWebClientAutoConfiguration::class.java,
                ),
            )
            .withBean(WaitCoordinator::class.java, { DefaultWaitCoordinator() })
            .withBean(WebClientCustomizer::class.java, { WebClientCustomizer { it.clientConnector(connector) } })
            .run { context ->
                context.getBean(CommandWaitNotifier::class.java)
                    .notify(WireSamples.COMMAND_WAIT_ENDPOINT, signal)
                    .test()
                    .verifyComplete()
                connector.uri.assert().isEqualTo(URI.create(WireSamples.COMMAND_WAIT_ENDPOINT))
                WireGolden.assertMatches(golden, checkNotNull(connector.body))

                val codecs = ServerCodecConfigurer.create()
                context.getBeanProvider(CodecCustomizer::class.java).orderedStream().forEach { it.customize(codecs) }
                val exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post(WireSamples.COMMAND_WAIT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(WireGolden.read(golden)),
                )
                val received = slot<WaitSignal>()
                val waitCoordinator = mockk<WaitCoordinator> {
                    every { signal(capture(received)) } returns true
                }
                CommandWaitHandlerFunction(waitCoordinator)
                    .handle(ServerRequest.create(exchange, codecs.readers))
                    .test()
                    .expectNextCount(1)
                    .verifyComplete()
                received.captured.assert().isEqualTo(signal)
            }
    }

    private class CapturingConnector : ClientHttpConnector {
        var uri: URI? = null
        var body: String? = null

        override fun connect(
            method: HttpMethod,
            uri: URI,
            requestCallback: Function<in ClientHttpRequest, Mono<Void>>
        ): Mono<ClientHttpResponse> {
            val request = MockClientHttpRequest(method, uri)
            return requestCallback.apply(request)
                .then(Mono.defer { request.bodyAsString })
                .map {
                    this.uri = uri
                    body = it
                    MockClientHttpResponse(HttpStatus.OK)
                }
        }
    }
}
