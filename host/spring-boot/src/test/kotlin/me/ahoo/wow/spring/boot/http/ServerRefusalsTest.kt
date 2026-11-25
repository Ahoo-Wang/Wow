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

package me.ahoo.wow.spring.boot.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.problem.LingeringClose
import me.ahoo.wow.spring.boot.HostTestApp
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.util.unit.DataSize
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyToFlux
import org.springframework.web.reactive.function.server.router
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture

/**
 * The public server's own refusals (#6956), on its real port with raw requests: what Reactor Netty refuses before any
 * route runs is a problem of its status, its detail naming the limit and the key that sets it, as every other error is.
 */
class ServerRefusalsTest {
    /** A response as the socket read it: its status, its headers by lower-case name, and its body. */
    class RawAnswer(val status: Int, val headers: Map<String, String>, val body: String) {
        val problem: JsonNode? get() = body.takeIf { it.isNotBlank() }?.let { json.readTree(it) }

        override fun toString(): String = "$status $headers ${body.take(300)}"
    }

    @Test
    fun `a header block past the server's limit should be a 431 problem naming the limit and its key`() {
        startHost().use { host ->
            val answer = raw(host.publicPort, get("/bank/account/a-1/state", "X-Pad: ${"a".repeat(9_000)}"))
            answer.status.assert().isEqualTo(431)
            answer.headers["content-type"].assert().isEqualTo("application/problem+json")
            answer.headers["connection"].assert().isEqualTo("close")
            val problem = answer.problem!!
            problem["status"].asInt().assert().isEqualTo(431)
            problem["title"].asString().assert().isEqualTo("Request Header Fields Too Large")
            problem["detail"].asString().assert().isEqualTo(
                "The request's headers are larger than this server takes: 8192 bytes " +
                    "(server.max-http-request-header-size).",
            )
            problem.has("code").assert().isFalse()
        }
    }

    @Test
    fun `a request line past the server's limit should be a 414 problem, an aggregate id past it included`() {
        startHost().use { host ->
            // The id rule refuses an id over 256 characters (400 wow.command.malformed); one long enough to make the
            // request line pass 4 KB is refused first, by the line's limit.
            val answer = raw(
                host.publicPort,
                post("/bank/account/${"a".repeat(5_000)}/deposit", """{"amount":1}"""),
            )
            answer.status.assert().isEqualTo(414)
            answer.headers["content-type"].assert().isEqualTo("application/problem+json")
            val problem = answer.problem!!
            problem["title"].asString().assert().isEqualTo("URI Too Long")
            problem["detail"].asString().assert().isEqualTo(
                "The request line is longer than this server takes: 4096 bytes (server.netty.max-initial-line-length).",
            )
            problem.has("code").assert().isFalse()
            // Within the line, the id rule answers.
            val within = raw(host.publicPort, post("/bank/account/${"a".repeat(1_000)}/deposit", """{"amount":1}"""))
            within.problem!!["code"].asString().assert().isEqualTo("wow.command.malformed")
        }
    }

    @Test
    fun `each limit's key should set it, and its detail should name the size it sets`() {
        startHost(
            "server.max-http-request-header-size" to "16KB",
            "server.netty.max-initial-line-length" to "2KB",
        ).use { host ->
            raw(host.publicPort, get("/bank/account/a-1/state", "X-Pad: ${"a".repeat(9_000)}")).status.assert()
                .isNotEqualTo(431)
            val line = raw(host.publicPort, post("/bank/account/${"a".repeat(3_000)}/deposit", """{"amount":1}"""))
            line.status.assert().isEqualTo(414)
            line.problem!!["detail"].asString().assert().contains("2048 bytes (server.netty.max-initial-line-length)")
        }
    }

    /**
     * Sets the header limit on the bound `ServerProperties` after binding, as CoSec's gateway mode derives it there (16
     * KB, `IDN-43`): the environment does not have it.
     */
    class HeaderLimitAfterBinding : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any = bean.also {
            if (it is ServerProperties) it.maxHttpRequestHeaderSize = DataSize.ofKilobytes(16)
        }
    }

    @Test
    fun `a header limit set on the server's properties after binding should be the one its detail names`() {
        startHost(sources = listOf(HostTestApp::class.java, HeaderLimitAfterBinding::class.java)).use { host ->
            raw(host.publicPort, get("/bank/account/a-1/state", "X-Pad: ${"a".repeat(9_000)}")).status.assert()
                .isNotEqualTo(431)
            val answer = raw(host.publicPort, get("/bank/account/a-1/state", "X-Pad: ${"a".repeat(17_000)}"))
            answer.status.assert().isEqualTo(431)
            answer.problem!!["detail"].asString().assert().isEqualTo(
                "The request's headers are larger than this server takes: 16384 bytes " +
                    "(server.max-http-request-header-size).",
            )
        }
    }

    @Test
    fun `a header limit should read from the server's properties, else from the environment's key, else as 8 KB`() {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "app",
                    mapOf(NodePublicPort.MAX_HEADER_SIZE to "12KB", NodePublicPort.MAX_INITIAL_LINE_LENGTH to "2KB"),
                ),
            )
        }
        val derived = ServerProperties().apply { maxHttpRequestHeaderSize = DataSize.ofKilobytes(16) }
        NodePublicPort.limitsOf(environment, derived).headers.assert()
            .isEqualTo("16384 bytes (server.max-http-request-header-size)")
        val bound = NodePublicPort.limitsOf(environment, null)
        bound.headers.assert().isEqualTo("12288 bytes (server.max-http-request-header-size)")
        bound.requestLine.assert().isEqualTo("2048 bytes (server.netty.max-initial-line-length)")
        val defaults = NodePublicPort.limitsOf(StandardEnvironment(), null)
        defaults.headers.assert().isEqualTo("8192 bytes (server.max-http-request-header-size)")
        defaults.requestLine.assert().isEqualTo("4096 bytes (server.netty.max-initial-line-length)")
    }

    @Test
    fun `a request that is no HTTP the server reads should be a 400 problem`() {
        startHost().use { host ->
            // Two Content-Length headers that disagree: Reactor Netty refuses the request before any route.
            val answer = raw(
                host.publicPort,
                (
                    "POST /bank/account/open-account HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                        "Content-Type: application/json\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n{}"
                    ).toByteArray(),
            )
            answer.status.assert().isEqualTo(400)
            answer.headers["content-type"].assert().isEqualTo("application/problem+json")
            answer.problem!!["detail"].asString().assert().isEqualTo("The request is not HTTP this server can read.")
            answer.problem!!.has("code").assert().isFalse()
        }
    }

    /**
     * Sent on a kept-alive connection and read by its `Content-Length`, not with `Connection: close`: Reactor Netty
     * drains the rest of a kept-alive request's body, but closes a closing one's unread, and a Linux server then resets
     * the connection before the client reads the 413 (#7001).
     */
    @Test
    fun `a body past the codecs' limit should be a 413 problem on the real server`() {
        startHost().use { host ->
            val body = """{"owner":"${"a".repeat(300_000)}"}"""
            val answer = keptAlive(host.publicPort, post("/bank/account/open-account", body, close = false))
            answer.status.assert().isEqualTo(413)
            answer.headers["content-type"].assert().isEqualTo("application/problem+json")
            answer.problem!!["detail"].asString().assert()
                .isEqualTo("The request body is larger than this server takes.")
        }
    }

    @Test
    fun `a body past the codecs' limit sent with Connection close should be answered 413, not reset (#7001)`() {
        startHost().use { host ->
            // The client writes its whole request at once, then reads: the 413 must reach it however early it was
            // written. On Linux, over epoll, the connection closed with the body unread was reset before it was read.
            val body = """{"owner":"${"a".repeat(300_000)}"}"""
            val answer = raw(host.publicPort, post("/bank/account/open-account", body))
            answer.status.assert().describedAs("$answer").isEqualTo(413)
            answer.headers["content-type"].assert().isEqualTo("application/problem+json")
        }
    }

    @Test
    fun `a body past the codecs' limit announced with Expect 100-continue should be answered 413 before it is sent`() {
        startHost().use { host ->
            // #7001: answered from its headers, in place of 100 Continue, so the client sends no body.
            val head = listOf(
                "POST /bank/account/open-account HTTP/1.1",
                "Host: 127.0.0.1",
                "Content-Type: application/json",
                "Content-Length: 300000",
                "Expect: 100-continue",
            ).joinToString("\r\n") + "\r\n\r\n"
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", host.publicPort), 1_000)
                socket.soTimeout = 5_000
                socket.getOutputStream().apply {
                    write(head.toByteArray())
                    flush()
                }
                val first = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
                first.assert().startsWith("HTTP/1.1 413")
            }
        }
    }

    /** An application's own route that reads its body as it arrives, past the codecs' limit: an upload. */
    @Configuration(proxyBeanMethods = false)
    class Uploads {
        @Bean
        fun uploadRoutes(): RouterFunction<ServerResponse> = router {
            POST("/uploads") { request ->
                request.bodyToFlux<DataBuffer>()
                    .map { buffer -> buffer.readableByteCount().also { DataBufferUtils.release(buffer) } }
                    .reduce(0L) { sum, bytes -> sum + bytes }
                    .flatMap { size -> ServerResponse.ok().bodyValue(size.toString()) }
            }
        }
    }

    @Test
    fun `an application's own route should take a body past the codecs' limit that it reads its own way (#7001)`() {
        startHost(sources = listOf(HostTestApp::class.java, Uploads::class.java)).use { host ->
            // Wow's routes refuse a body past the codecs' limit from its Content-Length; an application's route reads
            // its body under its own limits, so the server refuses none of it.
            val body = "x".repeat(1_024 * 1_024)
            val uploaded = keptAlive(host.publicPort, post("/uploads", body, close = false))
            uploaded.status.assert().describedAs("$uploaded").isEqualTo(200)
            uploaded.body.assert().isEqualTo(body.length.toString())
        }
    }

    @Test
    fun `a connection answered before its body was read should linger no longer than its bound (#7001)`() {
        startHost().use { host ->
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", host.publicPort), 1_000)
                client.soTimeout = 10_000
                // A body announced past the limit with Connection: close, a few bytes of it sent, and the client then
                // neither sending nor closing: the server answers 413 at once, and closes once its linger is over.
                client.getOutputStream().apply {
                    write(
                        (
                            "POST /bank/account/open-account HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n" +
                                "Content-Type: application/json\r\nContent-Length: 300000\r\n\r\n{\"owner\":\""
                            ).toByteArray(),
                    )
                    flush()
                }
                val started = System.nanoTime()
                // The answer, then the end of what the server sends: it shut its output down to linger.
                client.getInputStream().readAllBytes().toString(Charsets.UTF_8).assert().startsWith("HTTP/1.1 413")
                val lingered = writableFor(client, started)
                lingered.assert().isBetween(
                    LingeringClose.LINGER.inWholeMilliseconds - 1_000,
                    LingeringClose.LINGER.inWholeMilliseconds + 2_000,
                )
            }
        }
    }

    @Test
    fun `a lingering connection should end with the node's shutdown, not its linger (#7001)`() {
        val host = startHost()
        Socket().use { client ->
            client.connect(InetSocketAddress("127.0.0.1", host.publicPort), 1_000)
            client.soTimeout = 10_000
            client.getOutputStream().apply {
                write(
                    (
                        "POST /bank/account/open-account HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n" +
                            "Content-Type: application/json\r\nContent-Length: 300000\r\n\r\n{\"owner\":\""
                        ).toByteArray(),
                )
                flush()
            }
            client.getInputStream().readAllBytes().toString(Charsets.UTF_8).assert().startsWith("HTTP/1.1 413")
            // The connection lingers: what the client still sends is read, a second on.
            repeat(10) {
                client.getOutputStream().apply {
                    write('x'.code)
                    flush()
                }
                Thread.sleep(100)
            }
            // The server's dispose closes it with the server, the close asked again.
            val closing = System.nanoTime()
            CompletableFuture.runAsync { host.close() }
            val closed = writableFor(client, closing)
            closed.assert().describedAs("closed ${closed}ms after the shutdown began")
                .isLessThan(LingeringClose.LINGER.inWholeMilliseconds)
        }
    }

    @Test
    fun `a response a route writes should never be rewritten, however it looks`() {
        startHost().use { host ->
            // A refusal of Wow's own, on a connection the client closes: its code stays.
            val answer = raw(host.publicPort, post("/bank/account/a%00b/deposit", """{"amount":1}"""))
            answer.status.assert().isEqualTo(400)
            answer.problem!!["code"].asString().assert().isEqualTo("wow.command.malformed")
            // A refusal Netty made on one connection leaves the next one's answers alone.
            raw(host.publicPort, get("/bank/account/a-1/state", "X-Pad: ${"a".repeat(9_000)}")).status.assert()
                .isEqualTo(431)
            val next = raw(host.publicPort, get("/bank/account/no-such-account/state"))
            next.status.assert().isEqualTo(404)
            next.problem!!["code"].asString().assert().isNotBlank()
        }
    }

    internal companion object {
        private val json: JsonMapper = JsonMapper.builder().build()

        fun get(path: String, vararg headers: String): ByteArray {
            val head = listOf("GET $path HTTP/1.1", "Host: 127.0.0.1", "Connection: close", *headers)
            return (head.joinToString("\r\n") + "\r\n\r\n").toByteArray()
        }

        fun post(path: String, body: String, close: Boolean = true): ByteArray {
            val bytes = body.toByteArray()
            val head = listOfNotNull(
                "POST $path HTTP/1.1",
                "Host: 127.0.0.1",
                "Connection: close".takeIf { close },
                "Content-Type: application/json",
                "Content-Length: ${bytes.size}",
            )
            return (head.joinToString("\r\n") + "\r\n\r\n").toByteArray() + bytes
        }

        /** Writes [request] to [port] and reads until the server closes or nothing comes for [timeoutMillis]. */
        fun raw(port: Int, request: ByteArray, timeoutMillis: Int = 5_000): RawAnswer = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            socket.soTimeout = timeoutMillis
            socket.getOutputStream().apply {
                write(request)
                flush()
            }
            val read = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            try {
                while (true) {
                    val n = socket.getInputStream().read(buffer)
                    if (n < 0) break
                    read.write(buffer, 0, n)
                }
            } catch (_: SocketTimeoutException) {
                // The server keeps the connection: what came is the answer.
            }
            parse(read.toString(Charsets.UTF_8))
        }

        /**
         * How long, in milliseconds since [since], the server still reads what [client] sends: a lingering server reads
         * and discards it, and once it closes, a write is reset.
         */
        fun writableFor(client: Socket, since: Long): Long {
            val output = client.getOutputStream()
            val deadline = since + 20_000_000_000L
            while (System.nanoTime() < deadline) {
                val written = runCatching {
                    output.write('x'.code)
                    output.flush()
                }
                if (written.isFailure) break
                Thread.sleep(50)
            }
            return (System.nanoTime() - since) / 1_000_000
        }

        /** Writes [request] to [port] and reads one response on the kept-alive connection: its head, then its body. */
        fun keptAlive(port: Int, request: ByteArray): RawAnswer = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request)
                flush()
            }
            val input = socket.getInputStream()
            val read = ByteArrayOutputStream()
            while (!read.toString(Charsets.ISO_8859_1).contains("\r\n\r\n")) {
                val next = input.read()
                check(next >= 0) { "The connection closed before the response's head: $read" }
                read.write(next)
            }
            val head = parse(read.toString(Charsets.UTF_8))
            val body = ByteArray(head.headers.getValue("content-length").toInt())
            var at = 0
            while (at < body.size) {
                val n = input.read(body, at, body.size - at)
                check(n >= 0) { "The connection closed within the response's body." }
                at += n
            }
            RawAnswer(head.status, head.headers, body.toString(Charsets.UTF_8))
        }

        private fun parse(text: String): RawAnswer {
            val lines = text.substringBefore("\r\n\r\n").split("\r\n")
            val headers = lines.drop(1).filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            return RawAnswer(status, headers, text.substringAfter("\r\n\r\n", ""))
        }
    }
}
