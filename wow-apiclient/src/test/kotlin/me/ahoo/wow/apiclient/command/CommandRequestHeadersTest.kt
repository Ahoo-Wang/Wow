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

package me.ahoo.wow.apiclient.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.CommandStage
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import reactor.core.publisher.Mono

/**
 * The gateway methods that take the headers as one value send the same request as the ones with a parameter per
 * header.
 */
class CommandRequestHeadersTest {
    private val requests = mutableListOf<ClientRequest>()
    private val factory = HttpServiceProxyFactory.builderFor(
        WebClientAdapter.create(
            WebClient.builder().exchangeFunction { request ->
                requests.add(request)
                Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .build()
                )
            }.build()
        )
    ).build()

    @Test
    fun `every header of a full request`() {
        val request = CommandRequest(
            body = Body("value"),
            waitPlan = CommandRequest.WaitPlan(
                waitStage = CommandStage.SNAPSHOT,
                waitContext = "wait-context",
                waitProcessor = "wait-processor",
                waitTimeout = 5000,
            ),
            aggregateId = "aggregate-id",
            aggregateVersion = 3,
            tenantId = "tenant-id",
            ownerId = "owner-id",
            spaceId = "space-id",
            requestId = "request-id",
            localFirst = false,
            context = "context",
            aggregate = "aggregate",
            serviceUri = "http://service",
            type = "command-type",
        )
        request.toRequestHeaders().size.assert().isEqualTo(14)
        assertSameRequests(request)
    }

    @Test
    fun `a request with only its defaults`() {
        val request = CommandRequest(body = Body("value"), serviceUri = "http://service", type = "command-type")
        request.toRequestHeaders().keys.assert().hasSize(2)
        assertSameRequests(request)
    }

    private fun assertSameRequests(request: CommandRequest) {
        val reactive = factory.createClient(ReactiveRestCommandGateway::class.java)
        val sync = factory.createClient(SyncRestCommandGateway::class.java)

        reactive.send(request).block()
        reactive.send(request.sendUri, request.toRequestHeaders(), request.body).block()
        // No body comes back, so unwrapping the response fails after the request was sent.
        runCatching { sync.send(request) }
        sync.send(request.sendUri, request.toRequestHeaders(), request.body)

        requests.assert().hasSize(4)
        val sent = requests.map { it.url() to it.headers().asSingleValueMap() }
        sent.distinct().assert().hasSize(1)
        sent.first().first.toString().assert().isEqualTo("http://service/$COMMAND_SEND_ENDPOINT")
    }

    private data class Body(val value: String)
}
