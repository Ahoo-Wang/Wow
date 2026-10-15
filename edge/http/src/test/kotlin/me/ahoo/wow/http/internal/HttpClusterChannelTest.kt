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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.runtime.cluster.ClusterChannelException
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.ForwardReply
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import reactor.netty.ChannelBindException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The forward route and [HttpClusterChannel] over real HTTP on bare Reactor Netty (decisions D89, D109). */
class HttpClusterChannelTest {
    private val received = CopyOnWriteArrayList<ForwardCommand>()
    private val signals = CopyOnWriteArrayList<WaitSignal>()

    /** Executes every forward as committed, except the commands of context `slow`, which wait for [release]. */
    private val release = CompletableDeferred<Unit>()
    private val bank = ClusterChannelHandler { request ->
        received += request
        ForwardReply(result = committed(request.command.id))
    }
    private val slow = ClusterChannelHandler { request ->
        release.await()
        ForwardReply(result = committed(request.command.id, "slow"))
    }
    private val server = InternalServer(
        internalRoutes(
            ClusterChannelHandler { request ->
                if (request.command.ref.type.context == "slow") slow.forward(request) else bank.forward(request)
            },
            { signals += it },
        ),
    )
    private val client = InternalHttp.client()
    private val channel = HttpClusterChannel(client)

    @AfterEach
    fun close() {
        release.complete(Unit)
        server.close()
    }

    @Test
    fun `a forward is answered with the owner's reply over HTTP2`(): Unit = runBlocking {
        val request = forwardCommand()
        val reply = channel.forward(server.address, request)
        reply.result.assert().isEqualTo(committed("c-1"))
        reply.refused.assert().isNull()
        received.assert().containsExactly(request)

        channel.forward(server.address, forwardCommand("c-2")).result!!.commandId.assert().isEqualTo("c-2")
        server.versions.assert().containsOnly("HTTP/2.0")
    }

    @Test
    fun `connect opens the HTTP2 connection a forward then uses, once for any number of callers`(): Unit =
        runBlocking {
            coroutineScope { repeat(4) { launch { channel.connect(server.address) } } }
            channel.connect(server.address)
            // One request opened and upgraded the connection; the forward is the next one on it.
            server.versions.assert().hasSize(1)
            received.assert().isEmpty()
            channel.forward(server.address, forwardCommand()).result!!.commandId.assert().isEqualTo("c-1")
            server.versions.assert().hasSize(2)
            server.versions.last().assert().isEqualTo("HTTP/2.0")
        }

    @Test
    fun `once connected, a forward does not depend on the server taking a body on an upgrade request`(): Unit =
        runBlocking {
            InternalServer(internalRoutes(bank, {}), upgradeBody = 0).use { strict ->
                channel.connect(strict.address)
                channel.forward(strict.address, forwardCommand()).result!!.commandId.assert().isEqualTo("c-1")
            }
        }

    @Test
    fun `a peer that cannot be connected to fails, and the next connect tries again`(): Unit = runBlocking {
        // The server binds the port once the reservation is released; another process may take it in between, so
        // that is retried on a new port.
        var started: InternalServer? = null
        var attempts = 0
        while (started == null) {
            attempts++
            val port = withRefusingPort { port ->
                val address = "127.0.0.1:$port"
                failsWith<ClusterChannelException> { channel.connect(address) }.let { failure ->
                    failure.message.assert().contains(address)
                    failure.unsent.assert().isTrue()
                }
                port
            }
            started = try {
                InternalServer(internalRoutes(bank, {}), port = port)
            } catch (taken: ChannelBindException) {
                if (attempts == BIND_ATTEMPTS) throw taken
                null
            }
        }
        started.use {
            channel.connect(it.address)
            it.versions.assert().hasSize(1)
        }
    }

    @Test
    fun `a server that takes no body on an upgrade request fails the forward that opens a connection`(): Unit =
        runBlocking {
            InternalServer(internalRoutes(bank, {}), upgradeBody = 0).use { strict ->
                failsWith<ClusterChannelException> { channel.forward(strict.address, forwardCommand()) }
                    .message.assert().contains("413")
            }
        }

    @Test
    fun `a forward with fields this version does not know is read (D48)`(): Unit = runBlocking {
        val json = String(ClusterCodec.encode(forwardCommand()))
            .replaceFirst("{", """{"next":{"x":1},""")
            .replace(""""requestId":""", """"priority":3,"requestId":""")
        val response = post(InternalHttp.FORWARD_PATH, json)
        response.statusCode().assert().isEqualTo(200)
        ClusterCodec.decodeReply(response.body()).result!!.commandId.assert().isEqualTo("c-1")
        received.single().command.requestId.assert().isEqualTo("r-c-1")
    }

    @Test
    fun `a reply with fields this version does not know is read (D48)`(): Unit = runBlocking {
        val reply = String(ClusterCodec.encode(ForwardReply(result = committed("c-1"))))
            .replaceFirst("{", """{"next":[1,2],""")
        InternalServer(
            coRouter {
                POST(InternalHttp.FORWARD_PATH) {
                    ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(reply)
                }
            },
        ).use { newer ->
            channel.forward(newer.address, forwardCommand()).result.assert().isEqualTo(committed("c-1"))
        }
    }

    @Test
    fun `a body that is not a forward is answered 400`(): Unit = runBlocking {
        post(InternalHttp.FORWARD_PATH, "not json").statusCode().assert().isEqualTo(400)
        post(InternalHttp.FORWARD_PATH, """{"v":1}""").statusCode().assert().isEqualTo(400)
        post(InternalHttp.FORWARD_PATH, "null").statusCode().assert().isEqualTo(400)
        post(InternalHttp.FORWARD_PATH, "").statusCode().assert().isEqualTo(400)
        received.assert().isEmpty()
    }

    @Test
    fun `a signal is handed over and answered 204`(): Unit = runBlocking {
        val signal = WaitSignal(waitId = "w-1", result = committed("c-1"))
        val json = String(ClusterCodec.encode(signal)).replaceFirst("{", """{"next":true,""")
        post(InternalHttp.SIGNALS_PATH, json).statusCode().assert().isEqualTo(204)
        signals.assert().containsExactly(signal)

        post(InternalHttp.SIGNALS_PATH, """{"waitId":" "}""").statusCode().assert().isEqualTo(400)
        signals.assert().hasSize(1)
    }

    @Test
    fun `a status other than 200 fails the forward`(): Unit = runBlocking {
        InternalServer(coRouter { POST(InternalHttp.FORWARD_PATH) { ServerResponse.status(503).buildAndAwait() } })
            .use { failing ->
                val failure = failsWith<ClusterChannelException> { channel.forward(failing.address, forwardCommand()) }
                failure.message.assert().contains("503")
                // The peer answered: the request may have run there (decision D223).
                failure.unsent.assert().isFalse()
            }
        InternalServer(
            coRouter {
                POST(InternalHttp.FORWARD_PATH) {
                    ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait("""{"v":1}""")
                }
            },
        ).use { malformed ->
            failsWith<ClusterChannelException> { channel.forward(malformed.address, forwardCommand()) }
                .message.assert().contains("malformed")
        }
    }

    @Test
    fun `a peer that is not listening fails the forward`(): Unit = runBlocking {
        withRefusingPort { port ->
            failsWith<ClusterChannelException> { channel.forward("127.0.0.1:$port", forwardCommand()) }
                .unsent.assert().isTrue()
        }
    }

    @Test
    fun `a forward past its deadline fails by itself`(): Unit = runBlocking {
        val request = forwardCommand(context = "slow", deadline = System.currentTimeMillis() + 200)
        failsWith<ClusterChannelException> { channel.forward(server.address, request) }.unsent.assert().isFalse()
    }

    @Test
    fun `a caller that gives up leaves the owner's execution running`(): Unit = runBlocking {
        val executed = CompletableDeferred<String>()
        val owner = ClusterChannelHandler { request ->
            release.await()
            executed.complete(request.command.id)
            ForwardReply(result = committed(request.command.id))
        }
        InternalServer(internalRoutes(owner, {})).use { peer ->
            withTimeoutOrNull(200.milliseconds) { channel.forward(peer.address, forwardCommand()) }.assert().isNull()
            release.complete(Unit)
            withTimeoutOrNull(5.seconds) { executed.await() }.assert().isEqualTo("c-1")
        }
    }

    private fun post(path: String, body: String): HttpResponse<ByteArray> = client.send(
        HttpRequest.newBuilder(InternalHttp.uri(server.address, path))
            .header("Content-Type", InternalHttp.JSON)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    )
}
