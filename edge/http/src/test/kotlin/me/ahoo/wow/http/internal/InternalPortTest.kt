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
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.announce
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.Refusal
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger

/** The internal port (decisions D89, D109): its own server, bound and unbound by its host. */
class InternalPortTest {
    @Test
    fun `a built port should bind and unbind`() {
        val port = InternalPort.of(
            InternalPortSettings(port = 0, bindHost = "127.0.0.1"),
            coRouter { GET("/ping") { ServerResponse.ok().buildAndAwait() } },
        )
        port.isRunning.assert().isFalse()
        port.start()
        try {
            port.isRunning.assert().isTrue()
            port.boundPort.assert().isGreaterThan(0)
            port.start()
        } finally {
            port.stop()
        }
        port.isRunning.assert().isFalse()
    }

    @Test
    fun `a request whose body stalls should not hold the port's stop, which closes it (#6958)`() {
        val port = InternalPort.of(
            InternalPortSettings(port = 0, bindHost = "127.0.0.1"),
            coRouter { POST("/echo") { ServerResponse.ok().bodyValueAndAwait(it.awaitBody<String>()) } },
        )
        port.start()
        Socket().use { stalled ->
            stalled.connect(InetSocketAddress("127.0.0.1", port.boundPort), 1_000)
            stalled.getOutputStream().apply {
                write("POST /echo HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 100\r\n\r\n0123456789".toByteArray())
                flush()
            }
            Thread.sleep(300)
            val stopping = System.nanoTime()
            val stopped = runCatching { port.stop() }
            val took = (System.nanoTime() - stopping) / 1_000_000
            stopped.isSuccess.assert().isTrue()
            took.assert().isLessThan(2_000)
            // Closed with the port, not left open until the process ends.
            stalled.soTimeout = 1_000
            val end = runCatching { stalled.getInputStream().read() }
            (end.getOrNull() == -1 || end.exceptionOrNull() is SocketException).assert()
                .describedAs("the stalled connection after the stop: $end").isTrue()
        }
    }

    @Test
    fun `a forward or a signal announced past the limit is refused from its Content-Length, as when read (#7001)`() {
        val handler = ClusterChannelHandler { ForwardReply.refused(Refusal.NOT_OWNER) }
        val port = InternalPort.of(InternalPortSettings(port = 0, bindHost = "127.0.0.1"), internalRoutes(handler, {}))
        port.start()
        try {
            // Announced with Expect: 100-continue and not sent: the forward's TOO_LARGE reply, so its sender runs it,
            // and the signal's 413, each in place of 100 Continue.
            val forward = announce(port.boundPort, InternalHttp.FORWARD_PATH, headers = emptyList())
            forward.statusLine.assert().startsWith("HTTP/1.1 200")
            ClusterCodec.decodeReply(forward.bytes).refused.assert().isEqualTo(Refusal.TOO_LARGE)
            announce(port.boundPort, InternalHttp.SIGNALS_PATH, headers = emptyList()).statusLine.assert()
                .startsWith("HTTP/1.1 413")
        } finally {
            port.stop()
        }
    }

    @Test
    fun `over HTTP 2 a forward past the limit is its TOO_LARGE reply, a reset after it the protocol's way`() {
        // #7001 is HTTP/1.1's: an HTTP/2 stream answered before its body ends is reset after the complete response,
        // which the cluster channel's client reads; the lingering close does not apply to its streams.
        val handler = ClusterChannelHandler { ForwardReply.refused(Refusal.NOT_OWNER) }
        val port = InternalPort.of(InternalPortSettings(port = 0, bindHost = "127.0.0.1"), internalRoutes(handler, {}))
        port.start()
        try {
            val client = InternalHttp.client()
            val uri = URI.create("http://127.0.0.1:${port.boundPort}${InternalHttp.FORWARD_PATH}")
            val opened = client.send(
                HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            opened.version().assert().isEqualTo(HttpClient.Version.HTTP_2)
            val body = ("{\"x\":\"" + "a".repeat(300 * 1_024) + "\"}").toByteArray()
            val answer = client.send(
                HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            ClusterCodec.decodeReply(answer.body()).refused.assert().isEqualTo(Refusal.TOO_LARGE)
        } finally {
            port.stop()
        }
    }

    @Test
    fun `a port takes MAX_CONCURRENT_STREAMS streams on a connection, which the JDK client keeps to`(): Unit =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val arrived = AtomicInteger()
            val port = InternalPort.of(
                InternalPortSettings(port = 0, bindHost = "127.0.0.1"),
                coRouter {
                    GET("/ping") { ServerResponse.ok().buildAndAwait() }
                    GET("/held") {
                        arrived.incrementAndGet()
                        release.await()
                        ServerResponse.ok().buildAndAwait()
                    }
                },
            )
            port.start()
            try {
                val client = InternalHttp.client()
                fun get(path: String) = client.sendAsync(
                    HttpRequest.newBuilder(InternalHttp.uri("127.0.0.1:${port.boundPort}", path)).build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
                // The first request upgrades the connection to h2c; the others multiplex on it.
                get("/ping").await().statusCode().assert().isEqualTo(200)
                val held = (1..InternalHttp.MAX_CONCURRENT_STREAMS).map { get("/held") }
                eventually { arrived.get() == InternalHttp.MAX_CONCURRENT_STREAMS }
                val refused = runCatching { get("/held").join() }.exceptionOrNull()
                (refused as CompletionException).cause!!.isStreamLimit().assert().isTrue()
                release.complete(Unit)
                held.map { it.await().statusCode() }.assert().containsOnly(200)
            } finally {
                port.stop()
            }
        }
}
