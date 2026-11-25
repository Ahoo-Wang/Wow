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

package me.ahoo.wow.spring.boot.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spring.boot.HostTestApp
import me.ahoo.wow.spring.boot.http.NodeInternalPort
import me.ahoo.wow.spring.boot.node.ServiceNodeListener
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.env.MockEnvironment
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Deep review round 1 of the real server's limits: what a node on its Netty server answers at the edges an in-process
 * client never reaches, each request written as raw bytes on a socket.
 */
class NettyLimitsReviewR1Test {
    /** A response as the socket read it: its status line, its headers by lower-case name, and its body. */
    private class RawAnswer(val statusLine: String, val headers: Map<String, String>, val body: String) {
        val status: Int get() = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1

        override fun toString(): String = "$statusLine $headers ${body.take(300)}"
    }

    /** Writes [request] to [port] and reads until the server closes or [timeoutMillis] pass with nothing more. */
    private fun raw(port: Int, request: ByteArray, timeoutMillis: Int = 5_000): RawAnswer =
        Socket().use { socket ->
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

    private fun parse(text: String): RawAnswer {
        val head = text.substringBefore("\r\n\r\n")
        val lines = head.split("\r\n")
        val headers = lines.drop(1).filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return RawAnswer(lines.firstOrNull().orEmpty(), headers, text.substringAfter("\r\n\r\n", ""))
    }

    private fun request(method: String, path: String, headers: List<String>, body: String = ""): ByteArray {
        val lines = listOf("$method $path HTTP/1.1", "Host: 127.0.0.1") + headers +
            if (body.isEmpty() && method == "GET") emptyList() else listOf("Content-Length: ${body.toByteArray().size}")
        return (lines.joinToString("\r\n") + "\r\n\r\n" + body).toByteArray()
    }

    @Test
    fun `a request header past the server's limit should be answered as a problem, as every error is`() {
        startHost().use { host ->
            // reference/http.md, Problems: "Every error is application/problem+json". Reactor Netty's own refusal
            // of a header block past 8 KB (Spring Boot's default, which Wow neither sets nor documents) is not.
            val answer = raw(
                host.publicPort,
                request("GET", "/bank/account/a-1/state", listOf("X-Pad: ${"a".repeat(9_000)}", "Connection: close")),
            )
            answer.assert().describedAs("$answer").matches { it.status in 400..499 }
            answer.headers["content-type"].orEmpty().assert().describedAs("$answer")
                .startsWith("application/problem+json")
        }
    }

    @Test
    fun `an aggregate id past the request line's limit should be refused as the id rule says`() {
        startHost().use { host ->
            // reference/http.md: an id longer than 256 characters is "400 wow.command.malformed". One of 5,000
            // characters makes the request line longer than Reactor Netty's 4,096, which refuses it before Wow does.
            val answer = raw(
                host.publicPort,
                request(
                    "POST",
                    "/bank/account/${"a".repeat(5_000)}/deposit",
                    listOf("Content-Type: application/json", "Connection: close"),
                    """{"amount":1}""",
                ),
            )
            answer.status.assert().describedAs("$answer").isEqualTo(400)
            answer.body.assert().describedAs("$answer").contains("wow.command.malformed")
        }
    }

    @Test
    fun `the internal port's limit should follow the codec limit the public port applies`() {
        // Spring Boot 4.2 binds spring.http.codecs.max-in-memory-size alone: the older spring.codec key is gone from
        // its metadata, and the public port keeps WebFlux's 256 KiB under it. NodeInternalPort still reads it.
        startHost("spring.codec.max-in-memory-size" to "1MB").use { host ->
            val body = """{"owner":"${"a".repeat(300 * 1_024)}"}"""
            host.post("/bank/account/open-account", body).status.assert().isEqualTo(413)
            val applied = 256 * 1_024
            NodeInternalPort.publicCodecLimit(host.context.environment).assert()
                .describedAs("the public port applies $applied bytes")
                .isEqualTo(applied)
        }
        NodeInternalPort.publicCodecLimit(MockEnvironment().withProperty("spring.codec.max-in-memory-size", "1MB"))
            .assert().isEqualTo(256 * 1_024)
    }

    /** When the node's stop began: its first step, in `System.nanoTime()`. */
    class StopStart {
        val at = AtomicLong(0)
    }

    @Configuration(proxyBeanMethods = false)
    class StopRecording {
        @Bean
        fun stopStart(): StopStart = StopStart()

        @Bean
        fun stopStartRecorder(start: StopStart): ServiceNodeListener = ServiceNodeListener {
            start.at.compareAndSet(0, System.nanoTime())
        }
    }

    @Test
    fun `a request whose body stalls should hold the public server's shutdown no longer than the wait bound`() {
        // operate/topology.md: the public server's graceful shutdown "lasts as long as its longest request, up to
        // wow.wait.max-timeout"; the chart's termination grace is derived from it. Neither port sets a read timeout.
        val host = startHost(
            "wow.wait.max-timeout" to "3s",
            "wow.wait.default-timeout" to "3s",
            sources = listOf(HostTestApp::class.java, StopRecording::class.java),
        )
        val stopStart = host.context.getBean(StopStart::class.java)
        Socket().use { stalled ->
            stalled.connect(InetSocketAddress("127.0.0.1", host.publicPort), 1_000)
            // A Content-Length of 100, ten bytes of it sent, the rest never.
            stalled.getOutputStream().apply {
                write(
                    (
                        "POST /bank/account/open-account HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                            "Content-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"owner\":\""
                        ).toByteArray(),
                )
                flush()
            }
            Thread.sleep(300)
            val closing = System.nanoTime()
            val closed = CompletableFuture.runAsync { host.close() }
            closed.get(60, TimeUnit.SECONDS)
            val held = TimeUnit.NANOSECONDS.toMillis(stopStart.at.get() - closing)
            // The wait bound, and a margin for the readiness change before it.
            held.assert().describedAs("the node began to stop ${held}ms after the close").isLessThan(3_000 + 1_500)
        }
    }
}
