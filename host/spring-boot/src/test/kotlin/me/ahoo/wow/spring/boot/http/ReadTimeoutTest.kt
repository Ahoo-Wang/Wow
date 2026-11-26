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
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.HostTestApp
import me.ahoo.wow.spring.boot.startHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyToFlux
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration

/**
 * `wow.http.read-timeout` on both ports of a host, with raw requests: a body that stalls, nothing of it read for the
 * timeout, ends its request, before its answer or after it; a body that keeps arriving, an answer however slow and a
 * connection idle between its requests are not bounded.
 */
@ExtendWith(OutputCaptureExtension::class)
class ReadTimeoutTest {
    @Test
    fun `a body that stalls past the read timeout should be answered 408 naming the key, its connection closed`(
        output: CapturedOutput,
    ) {
        startHost(TIMEOUT).use { host ->
            Socket().use { client ->
                client.open(host.publicPort)
                client.send(head("/bank/account/a-1/deposit", length = 100) + """{"amount":""")
                val answer = client.getInputStream().readAllBytes().toString(Charsets.UTF_8)
                answer.assert().startsWith("HTTP/1.1 408").contains("application/problem+json").contains(
                    "The request's body stalled longer than this server waits: 500ms (wow.http.read-timeout).",
                )
            }
        }
        // A client's stall is no failure of the server's: neither the route nor Reactor Netty logs one.
        output.out.assert().doesNotContain("unexpected error").doesNotContain("onErrorDropped")
    }

    @Test
    fun `a body answered before it was read should have its connection closed once it stalls, on both ports`() {
        startHost(TIMEOUT).use { host ->
            // A 404 on the public port and a 401 on the internal one, each answered from the headers, the body unread,
            // on a kept-alive connection: the server reads the rest to take the next request, until it stalls.
            listOf(
                Triple(host.publicPort, "/no/such/route", "HTTP/1.1 404"),
                Triple(host.internalPort, "/wow/internal/forward", "HTTP/1.1 401"),
            ).forEach { (port, path, status) ->
                Socket().use { client ->
                    client.open(port)
                    client.send(head(path, length = 100, close = false) + "{")
                    val read = client.getInputStream().readUntilClosed()
                    read.text.assert().startsWith(status)
                    read.closed.assert().describedAs("$port closed after its answer: ${read.text}").isTrue()
                }
            }
        }
    }

    /** An application's route that reads its body as it arrives, then answers a second later. */
    @Configuration(proxyBeanMethods = false)
    class SlowRoutes {
        @Bean
        fun slowRoutes(): RouterFunction<ServerResponse> = router {
            POST("/uploads") { request ->
                request.bodyToFlux<DataBuffer>()
                    .map { buffer -> buffer.readableByteCount().also { DataBufferUtils.release(buffer) } }
                    .reduce(0L) { sum, bytes -> sum + bytes }
                    .delayElement(Duration.ofSeconds(1))
                    .flatMap { size -> ServerResponse.ok().bodyValue(size.toString()) }
            }
            GET("/later") { Mono.delay(Duration.ofSeconds(1)).flatMap { ServerResponse.ok().bodyValue("later") } }
        }
    }

    @Test
    fun `a body that keeps arriving, a slow answer and an idle connection should not be bounded`() {
        startHost(TIMEOUT, sources = listOf(HostTestApp::class.java, SlowRoutes::class.java)).use { host ->
            Socket().use { client ->
                client.open(host.publicPort)
                // Ten pieces 200 ms apart, two seconds in all: each gap is within the timeout, the whole is not.
                client.send(head("/uploads", length = 10, close = false))
                repeat(10) {
                    Thread.sleep(200)
                    client.send("x".toByteArray())
                }
                // Answered a second after the body's end: the answer is not bounded.
                val uploaded = client.getInputStream().readAnswer()
                uploaded.assert().startsWith("HTTP/1.1 200").endsWith("\r\n\r\n10")
                // The connection, idle between its requests for longer than the timeout, takes the next one.
                Thread.sleep(1_000)
                client.send(
                    "GET /later HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(),
                )
                client.getInputStream().readAllBytes().toString(Charsets.UTF_8).assert().startsWith("HTTP/1.1 200")
            }
        }
    }

    @Test
    fun `a body answered before it was read should be read on while it keeps arriving, its connection kept`() {
        startHost(TIMEOUT).use { host ->
            Socket().use { client ->
                client.open(host.publicPort)
                client.send(head("/no/such/route", length = 5, close = false))
                client.getInputStream().readAnswer().assert().startsWith("HTTP/1.1 404")
                // The rest of the body, 300 ms apart, read on and discarded; then the next request, on the same
                // connection.
                repeat(5) {
                    Thread.sleep(300)
                    client.send("x".toByteArray())
                }
                client.send(head("/no/such/route", length = 0))
                client.getInputStream().readAllBytes().toString(Charsets.UTF_8).assert().startsWith("HTTP/1.1 404")
            }
        }
    }

    @Test
    fun `the read timeout should be 30 seconds by default, and one that is not positive should refuse the start`() {
        HttpProperties().checkedReadTimeout().inWholeSeconds.assert().isEqualTo(30)
        listOf("0s", "-1s").forEach { timeout ->
            val failure = assertThrows<Exception> { startHost("wow.http.read-timeout" to timeout).close() }
            val refusal = generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<WowConfigurationException>().first()
            refusal.key.assert().isEqualTo("wow.http.read-timeout")
        }
    }

    /** What was read of a connection, and whether the server closed it before the client's own timeout. */
    class Read(val text: String, val closed: Boolean)

    private companion object {
        val TIMEOUT: Pair<String, Any> = "wow.http.read-timeout" to "500ms"

        fun Socket.open(port: Int) {
            connect(InetSocketAddress("127.0.0.1", port), 1_000)
            soTimeout = 10_000
        }

        fun Socket.send(bytes: ByteArray) {
            getOutputStream().apply {
                write(bytes)
                flush()
            }
        }

        fun Socket.send(text: String) = send(text.toByteArray())

        fun head(path: String, length: Int, close: Boolean = true): ByteArray = listOfNotNull(
            "POST $path HTTP/1.1",
            "Host: 127.0.0.1",
            "Connection: close".takeIf { close },
            "Content-Type: application/json",
            "Content-Length: $length",
        ).joinToString("\r\n", postfix = "\r\n\r\n").toByteArray()

        operator fun ByteArray.plus(text: String): ByteArray = this + text.toByteArray()

        /** Reads until the server closes, or the client's timeout passes. */
        fun InputStream.readUntilClosed(): Read {
            val read = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            val closed = runCatching {
                while (true) {
                    val n = read(buffer)
                    if (n < 0) break
                    read.write(buffer, 0, n)
                }
            }.isSuccess
            return Read(read.toString(Charsets.UTF_8), closed)
        }

        /** Reads one answer of a kept-alive connection: its head, then its body by its `Content-Length`. */
        fun InputStream.readAnswer(): String {
            val head = ByteArrayOutputStream()
            while (!head.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                val next = read()
                check(next >= 0) { "The connection closed before the answer's head: $head" }
                head.write(next)
            }
            val text = head.toString(Charsets.ISO_8859_1)
            val length = Regex("(?i)content-length: *(\\d+)").find(text)!!.groupValues[1].toInt()
            return text + String(readNBytes(length), Charsets.UTF_8)
        }
    }
}
