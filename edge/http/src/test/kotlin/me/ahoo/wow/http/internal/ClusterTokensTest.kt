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

package me.ahoo.wow.http.internal

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.client.ServiceCredential
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.caller.ServiceTokens
import me.ahoo.wow.runtime.cluster.ClusterChannelException
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The cluster routes of the internal port take the service's cluster token alone (decision D226, review A3-1): a
 * forward without it is 401, with another one 403, and never runs; a service token opens no cluster route; a signal
 * from another service's node is taken by that service's identity.
 */
class ClusterTokensTest {
    private val received = CopyOnWriteArrayList<ForwardCommand>()
    private val signals = CopyOnWriteArrayList<WaitSignal>()
    private val services = ServiceTokens(mapOf("ordering-service" to listOf(SERVICE_TOKEN)))
    private val server = InternalServer(
        internalRoutes(
            ClusterChannelHandler { request ->
                received += request
                ForwardReply(result = committed(request.command.id))
            },
            { signals += it },
            tokens = ClusterTokens(listOf(CURRENT, PREVIOUS)),
            services = services.resolver { null },
        ),
    )
    private val client = InternalHttp.client()

    @AfterEach
    fun close() {
        server.close()
    }

    @Test
    fun `a forward with the cluster token runs, with the token being rotated too`(): Unit = runBlocking {
        HttpClusterChannel(client, token = CURRENT).forward(server.address, forwardCommand("c-1"))
            .result!!.commandId.assert().isEqualTo("c-1")
        HttpClusterChannel(client, token = PREVIOUS).forward(server.address, forwardCommand("c-2"))
            .result!!.commandId.assert().isEqualTo("c-2")
        received.map { it.command.id }.assert().containsExactly("c-1", "c-2")
    }

    @Test
    fun `a forward without the token is 401 and with another one 403, and never runs`(): Unit = runBlocking {
        val missing = post(InternalHttp.FORWARD_PATH, forwardCommand())
        missing.statusCode().assert().isEqualTo(401)
        String(missing.body()).assert().contains("wow.auth.unauthenticated")
        missing.headers().firstValue("Content-Type").get().assert().contains("problem+json")
        val wrong = post(InternalHttp.FORWARD_PATH, forwardCommand(), mapOf(InternalHttp.CLUSTER_TOKEN to OTHER))
        wrong.statusCode().assert().isEqualTo(403)
        String(wrong.body()).assert().contains("wow.auth.forbidden")
        received.assert().isEmpty()
    }

    @Test
    fun `a service token opens no cluster route`(): Unit = runBlocking {
        val bearer = mapOf("Authorization" to "Bearer $SERVICE_TOKEN")
        post(InternalHttp.FORWARD_PATH, forwardCommand(), bearer).statusCode().assert().isEqualTo(403)
        // Not as the cluster token either.
        post(InternalHttp.FORWARD_PATH, forwardCommand(), mapOf(InternalHttp.CLUSTER_TOKEN to SERVICE_TOKEN))
            .statusCode().assert().isEqualTo(403)
        received.assert().isEmpty()
    }

    @Test
    fun `a refused token is a refusal the sender runs itself (D223)`(): Unit = runBlocking {
        val failure = failsWith<ClusterChannelException> {
            HttpClusterChannel(client, token = OTHER).forward(server.address, forwardCommand())
        }
        failure.unsent.assert().isTrue()
        failure.message.assert().contains("403")
        failsWith<ClusterChannelException> { HttpClusterChannel(client).forward(server.address, forwardCommand()) }
            .unsent.assert().isTrue()
        received.assert().isEmpty()
    }

    @Test
    fun `a signal takes the cluster token, or a trusted service's identity, and nothing else`(): Unit = runBlocking {
        val signal = WaitSignal(waitId = "w-1", result = committed("c-1"))
        post(InternalHttp.SIGNALS_PATH, signal, mapOf(InternalHttp.CLUSTER_TOKEN to PREVIOUS))
            .statusCode().assert().isEqualTo(204)
        post(InternalHttp.SIGNALS_PATH, signal, mapOf("Authorization" to "Bearer $SERVICE_TOKEN"))
            .statusCode().assert().isEqualTo(204)
        post(InternalHttp.SIGNALS_PATH, signal).statusCode().assert().isEqualTo(401)
        post(InternalHttp.SIGNALS_PATH, signal, mapOf("Authorization" to "Bearer $OTHER"))
            .statusCode().assert().isEqualTo(403)
        post(InternalHttp.SIGNALS_PATH, signal, mapOf(InternalHttp.CLUSTER_TOKEN to OTHER))
            .statusCode().assert().isEqualTo(403)
        signals.assert().hasSize(2)
    }

    @Test
    fun `a signal route without a service resolver takes the cluster token alone`(): Unit = runBlocking {
        val signals = CopyOnWriteArrayList<WaitSignal>()
        InternalServer(
            internalRoutes(
                ClusterChannelHandler { ForwardReply(result = committed(it.command.id)) },
                { signals += it },
                tokens = ClusterTokens(listOf(CURRENT)),
                services = CallerResolver { null },
            ),
        ).use { strict ->
            val signal = WaitSignal(waitId = "w-1", result = committed("c-1"))
            val request = HttpRequest.newBuilder(InternalHttp.uri(strict.address, InternalHttp.SIGNALS_PATH))
                .header("Authorization", "Bearer $SERVICE_TOKEN")
                .POST(HttpRequest.BodyPublishers.ofByteArray(ClusterCodec.encode(signal)))
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode().assert().isEqualTo(403)
            signals.assert().isEmpty()
        }
    }

    @Test
    fun `a sender signals its own service with the cluster token and another one with its identity`(): Unit =
        runBlocking {
            HttpSignalSender(
                client,
                service = "bank-service",
                clusterToken = CURRENT,
                credential = ServiceCredential.bearer(SERVICE_TOKEN),
                members = { it == server.address },
            ).use { sender ->
                sender.send(
                    Origin("node-1", server.address, "bank-service"),
                    WaitSignal(waitId = "w-own", result = committed("c-1")),
                    deadline(),
                )
                sender.send(
                    Origin("node-2", server.address, "ordering-service"),
                    WaitSignal(waitId = "w-x", result = committed("c-2")),
                    deadline(),
                )
                eventually { signals.size == 2 }
                signals.map { it.waitId }.assert().containsExactlyInAnyOrder("w-own", "w-x")
            }
            // The cluster token never goes to another service: without an identity, its origin refuses the signal.
            HttpSignalSender(client, service = "bank-service", clusterToken = CURRENT).use { sender ->
                sender.send(
                    Origin("node-2", server.address, "ordering-service"),
                    WaitSignal(waitId = "w-y", result = committed("c-3")),
                    deadline(),
                )
                eventually { sender.dropped == 1L }
            }
            signals.assert().hasSize(2)
        }

    @Test
    fun `a sender sends the cluster token to no address outside its cluster`(): Unit = runBlocking {
        // An origin naming this service, at an address the lease store does not show as a member: whoever started or
        // carried the wait chose it, so it is sent no credential, and a node of this service refuses it.
        HttpSignalSender(
            client,
            service = "bank-service",
            clusterToken = CURRENT,
            credential = ServiceCredential.bearer(SERVICE_TOKEN),
            members = { it == "10.0.0.1:9191" },
        ).use { sender ->
            sender.send(
                Origin("node-1", server.address, "bank-service"),
                WaitSignal(waitId = "w-exfiltrated", result = committed("c-1")),
                deadline(),
            )
            eventually { sender.dropped == 1L }
        }
        // Without members, the same.
        HttpSignalSender(client, service = "bank-service", clusterToken = CURRENT).use { sender ->
            sender.send(
                Origin("node-1", server.address, "bank-service"),
                WaitSignal(waitId = "w-unknown", result = committed("c-2")),
                deadline(),
            )
            eventually { sender.dropped == 1L }
        }
        signals.assert().isEmpty()
    }

    @Test
    fun `a cluster token is long enough`() {
        assertThrownBy<IllegalArgumentException> { ClusterTokens(listOf("short")) }
        ClusterTokens.NONE.isEmpty.assert().isTrue()
        ClusterTokens.NONE.current.assert().isNull()
        ClusterTokens(listOf(CURRENT, PREVIOUS)).current.assert().isEqualTo(CURRENT)
    }

    private fun post(path: String, message: Any, headers: Map<String, String> = emptyMap()): HttpResponse<ByteArray> {
        val body = when (message) {
            is ForwardCommand -> ClusterCodec.encode(message)
            is WaitSignal -> ClusterCodec.encode(message)
            else -> error("Not a cluster message: $message")
        }
        val request = HttpRequest.newBuilder(InternalHttp.uri(server.address, path))
            .header("Content-Type", InternalHttp.JSON)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private companion object {
        const val CURRENT = "cluster-token-current-0123456789abcdef"
        const val PREVIOUS = "cluster-token-previous-0123456789abcdef"
        const val OTHER = "cluster-token-of-another-service-0123456789"
        const val SERVICE_TOKEN = "service-token-of-ordering-0123456789abcdef"
    }
}
