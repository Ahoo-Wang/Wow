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

package me.ahoo.wow.client

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * What the transport relies on of the JDK's `HttpClient` (#6993): its request timeout, firing before the connection
 * is made, says so with an [HttpConnectTimeoutException], for a request with a body and for one without; firing on a
 * connection made and not answered, it says [HttpTimeoutException] and not that. The transport takes the first as
 * unsent and fails over ([HttpTransport]'s `within`), so a JDK that said the second there would leave a connect that
 * is not made as an unknown outcome again.
 */
class JdkConnectVerdictTest {
    private val client = HttpCommandCaller.client()

    private fun requests(uri: URI): List<HttpRequest> = listOf(
        HttpRequest.newBuilder(uri).timeout(TIMEOUT).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
        HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(),
    )

    @Test
    fun `a request timeout before the connection is made should be a connect timeout, with a body or without`() {
        withPendingConnect { port ->
            requests(URI.create("http://127.0.0.1:$port/x")).forEach { request ->
                val failure = assertThrows<IOException> { client.send(request, HttpResponse.BodyHandlers.discarding()) }
                failure.assert().describedAs("${request.method()}: $failure")
                    .isInstanceOf(HttpConnectTimeoutException::class.java)
            }
        }
    }

    @Test
    fun `a request timeout on a connection made and not answered should not be a connect timeout`() {
        FakePeer().reply(Reply.HELD).use { held ->
            requests(held.base.resolve("/x")).forEach { request ->
                val failure = assertThrows<IOException> { client.send(request, HttpResponse.BodyHandlers.discarding()) }
                failure.assert().describedAs("${request.method()}: $failure")
                    .isInstanceOf(HttpTimeoutException::class.java)
                    .isNotInstanceOf(HttpConnectTimeoutException::class.java)
            }
        }
    }

    private companion object {
        /** Shorter than the client's connect timeout, so the request timeout fires first. */
        val TIMEOUT: Duration = Duration.ofMillis(300)
    }
}

/**
 * Runs [block] with a loopback port a connection to which stays pending, neither made nor refused, or skips the test,
 * saying why, where the OS gives none. macOS leaves a connection to a port bound without listening unanswered; Linux
 * refuses that one, but drops the connections an accept queue that is full cannot take, so the queue of a socket that
 * listens and never accepts is filled first.
 */
fun withPendingConnect(block: (port: Int) -> Unit) {
    Socket().use { bound ->
        bound.bind(InetSocketAddress("127.0.0.1", 0))
        if (pending(bound.localPort)) {
            block(bound.localPort)
            return
        }
    }
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { full ->
        val fillers = mutableListOf<Socket>()
        try {
            assumeTrue(filled(full, fillers), "this OS leaves no loopback connection pending: it refuses both ways")
            block(full.localPort)
        } finally {
            fillers.forEach(Socket::close)
        }
    }
}

/** Whether [full]'s accept queue, filled with [fillers], now leaves the next connection to it pending. */
private fun filled(full: ServerSocket, fillers: MutableList<Socket>): Boolean {
    repeat(MAX_FILLERS) {
        val filler = Socket()
        try {
            filler.connect(full.localSocketAddress, PROBE_MILLIS)
            fillers += filler
        } catch (timedOut: SocketTimeoutException) {
            filler.close()
            return true
        } catch (refused: IOException) {
            filler.close()
            return false
        }
    }
    return false
}

/** Whether a connection to [port] is neither made nor refused within a probe's time. */
private fun pending(port: Int): Boolean = Socket().use { probe ->
    try {
        probe.connect(InetSocketAddress("127.0.0.1", port), PROBE_MILLIS)
        false
    } catch (timedOut: SocketTimeoutException) {
        true
    } catch (refused: IOException) {
        false
    }
}

private const val PROBE_MILLIS = 200
private const val MAX_FILLERS = 64
