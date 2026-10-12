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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.runtime.cluster.ClusterChannelException
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.Refusal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import java.net.ServerSocket
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
        internalRoutes(ContextDispatchingHandler(mapOf(TEST_CONTEXT to bank, "slow" to slow)), { signals += it }),
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
    fun `a server that takes no body on an upgrade request fails the forward that opens a connection`(): Unit =
        runBlocking {
            InternalServer(internalRoutes(bank, {}), upgradeBody = 0).use { strict ->
                failsWith<ClusterChannelException> { channel.forward(strict.address, forwardCommand()) }
                    .message.assert().contains("413")
            }
        }

    @Test
    fun `a context this node does not host is refused as an unknown type`(): Unit = runBlocking {
        val reply = channel.forward(server.address, forwardCommand(context = "elsewhere"))
        reply.refused.assert().isEqualTo(Refusal.UNKNOWN_TYPE)
        reply.result.assert().isNull()
        received.assert().isEmpty()
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
        val json = String(SignalCodec.encode(signal)).replaceFirst("{", """{"next":true,""")
        post(InternalHttp.SIGNALS_PATH, json).statusCode().assert().isEqualTo(204)
        signals.assert().containsExactly(signal)

        post(InternalHttp.SIGNALS_PATH, """{"waitId":" "}""").statusCode().assert().isEqualTo(400)
        signals.assert().hasSize(1)
    }

    @Test
    fun `a status other than 200 fails the forward`(): Unit = runBlocking {
        InternalServer(coRouter { POST(InternalHttp.FORWARD_PATH) { ServerResponse.status(503).buildAndAwait() } })
            .use { failing ->
                failsWith<ClusterChannelException> { channel.forward(failing.address, forwardCommand()) }
                    .message.assert().contains("503")
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
        val port = ServerSocket(0).use { it.localPort }
        failsWith<ClusterChannelException> { channel.forward("127.0.0.1:$port", forwardCommand()) }
    }

    @Test
    fun `a forward past its deadline fails by itself`(): Unit = runBlocking {
        val request = forwardCommand(context = "slow", deadline = System.currentTimeMillis() + 200)
        failsWith<ClusterChannelException> { channel.forward(server.address, request) }
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
