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
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.api.wire.WowHeaders
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
 * The cluster routes of the internal port take the service a request names (decision D300): a forward from a node of
 * the same service runs, one naming no service is 401 and one naming another 403, and never runs; a signal is taken
 * from any service that names itself, the origin of a wait that crossed services.
 */
class DeclaredServiceRoutesTest {
    private val received = CopyOnWriteArrayList<ForwardCommand>()
    private val signals = CopyOnWriteArrayList<WaitSignal>()
    private val server = InternalServer(
        internalRoutes(
            ClusterChannelHandler { request ->
                received += request
                ForwardReply(result = committed(request.command.id))
            },
            { signals += it },
            service = SERVICE,
        ),
    )
    private val client = InternalHttp.client()

    @AfterEach
    fun close() {
        server.close()
    }

    @Test
    fun `a forward from a node of the same service runs`(): Unit = runBlocking {
        HttpClusterChannel(client, service = SERVICE).forward(server.address, forwardCommand("c-1"))
            .result!!.commandId.assert().isEqualTo("c-1")
        received.map { it.command.id }.assert().containsExactly("c-1")
    }

    @Test
    fun `a forward naming no service is 401 and another service 403, and never runs`(): Unit = runBlocking {
        val missing = post(InternalHttp.FORWARD_PATH, forwardCommand())
        missing.statusCode().assert().isEqualTo(401)
        String(missing.body()).assert().contains("wow.auth.unauthenticated")
        missing.headers().firstValue("Content-Type").get().assert().contains("problem+json")
        val other = post(InternalHttp.FORWARD_PATH, forwardCommand(), mapOf(WowHeaders.SERVICE to OTHER))
        other.statusCode().assert().isEqualTo(403)
        String(other.body()).assert().contains("wow.auth.forbidden").contains(OTHER)
        // A value that is no service name names none.
        post(InternalHttp.FORWARD_PATH, forwardCommand(), mapOf(WowHeaders.SERVICE to "bank service"))
            .statusCode().assert().isEqualTo(401)
        received.assert().isEmpty()
    }

    @Test
    fun `a refused forward is a refusal the sender runs itself (D223)`(): Unit = runBlocking {
        val failure = failsWith<ClusterChannelException> {
            HttpClusterChannel(client, service = OTHER).forward(server.address, forwardCommand())
        }
        failure.unsent.assert().isTrue()
        failure.message.assert().contains("403")
        failsWith<ClusterChannelException> { HttpClusterChannel(client).forward(server.address, forwardCommand()) }
            .unsent.assert().isTrue()
        received.assert().isEmpty()
    }

    @Test
    fun `a signal is taken from any service that names itself`(): Unit = runBlocking {
        val signal = WaitSignal(waitId = "w-1", result = committed("c-1"))
        post(InternalHttp.SIGNALS_PATH, signal, mapOf(WowHeaders.SERVICE to SERVICE)).statusCode().assert()
            .isEqualTo(204)
        post(InternalHttp.SIGNALS_PATH, signal, mapOf(WowHeaders.SERVICE to OTHER)).statusCode().assert()
            .isEqualTo(204)
        post(InternalHttp.SIGNALS_PATH, signal).statusCode().assert().isEqualTo(401)
        signals.assert().hasSize(2)
    }

    @Test
    fun `a sender names its service to its own nodes and to another service's`(): Unit = runBlocking {
        HttpSignalSender(client, service = OTHER).use { sender ->
            sender.send(
                Origin("node-1", server.address, SERVICE),
                WaitSignal(waitId = "w-x", result = committed("c-1")),
                deadline(),
            )
            eventually { signals.size == 1 }
        }
        // A sender that names no service is refused, and drops the signal.
        HttpSignalSender(client).use { sender ->
            sender.send(
                Origin("node-1", server.address, SERVICE),
                WaitSignal(waitId = "w-y", result = committed("c-2")),
                deadline(),
            )
            eventually { sender.dropped == 1L }
        }
        signals.map { it.waitId }.assert().containsExactly("w-x")
    }

    @Test
    fun `without a service the routes take every request`(): Unit = runBlocking {
        InternalServer(
            internalRoutes(ClusterChannelHandler { ForwardReply(result = committed(it.command.id)) }, { signals += it }),
        ).use { open ->
            HttpClusterChannel(client).forward(open.address, forwardCommand("c-9")).result!!.commandId.assert()
                .isEqualTo("c-9")
        }
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
        const val SERVICE = "bank-service"
        const val OTHER = "ordering-service"
    }
}
