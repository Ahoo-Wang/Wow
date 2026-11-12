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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.transport.TransportOptions
import co.elastic.clients.transport.http.TransportHttpClient
import co.elastic.clients.transport.rest5_client.low_level.Request
import co.elastic.clients.transport.rest5_client.low_level.Response
import co.elastic.clients.transport.rest5_client.low_level.ResponseListener
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.store.StoreConfig
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.util.Timeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ElasticsearchTimeoutsTest {
    /** A client whose every request is [sent], answered or failed by the test, and that records it. */
    private open class HeldClient : TransportHttpClient {
        open val sent = CompletableFuture<TransportHttpClient.Response>()

        override fun performRequest(
            endpointId: String,
            node: TransportHttpClient.Node?,
            request: TransportHttpClient.Request,
            options: TransportOptions?,
        ): TransportHttpClient.Response = throw UnsupportedOperationException()

        override fun performRequestAsync(
            endpointId: String,
            node: TransportHttpClient.Node?,
            request: TransportHttpClient.Request,
            options: TransportOptions?,
        ): CompletableFuture<TransportHttpClient.Response> = sent

        override fun close() = Unit
    }

    private val request = TransportHttpClient.Request("GET", "/", emptyMap(), emptyMap(), null)

    private fun send(held: HeldClient, timeout: Duration) =
        BoundedHttpClient(held, timeout).performRequestAsync("get", null, request, null)

    @Test
    fun `a request with no answer within the timeout should fail its caller, and not be cancelled`() {
        val held = HeldClient()
        val started = TimeSource.Monotonic.markNow()
        val failure = assertThrows<ExecutionException> { send(held, 100.milliseconds).get(5, TimeUnit.SECONDS) }
        started.elapsedNow().assert().isLessThan(2.seconds)
        failure.cause.assert().isInstanceOf(SocketTimeoutException::class.java)
        failure.cause!!.message.assert().isEqualTo("Elasticsearch gave no answer to get within 100ms.")
        // The request is the client's: a cancel frees no reused connection, and one before its bytes are written leaks.
        Thread.sleep(200)
        held.sent.isCancelled.assert().isFalse()
    }

    @Test
    fun `an answer in time should be the request's, and its deadline should not fire after it`() {
        val held = HeldClient()
        val response = mockk<TransportHttpClient.Response>(relaxed = true)
        val answered = send(held, 300.milliseconds)
        held.sent.complete(response)
        answered.get(1, TimeUnit.SECONDS).assert().isSameAs(response)
        Thread.sleep(500)
        answered.isCompletedExceptionally.assert().isFalse()
        verify(exactly = 0) { response.close() }
    }

    @Test
    fun `an answer after the timeout should be released`() {
        val held = HeldClient()
        val answered = send(held, 50.milliseconds)
        assertThrownBy<ExecutionException> { answered.get(5, TimeUnit.SECONDS) }
        val response = mockk<TransportHttpClient.Response>(relaxed = true)
        held.sent.complete(response)
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `a failure should be the request's`() {
        val held = HeldClient()
        val answered = send(held, 1.seconds)
        held.sent.completeExceptionally(IOException("refused"))
        assertThrows<ExecutionException> { answered.get(1, TimeUnit.SECONDS) }.cause!!.message
            .assert().isEqualTo("refused")
    }

    @Test
    fun `a caller giving up should end its wait alone, the request left running`() {
        val held = HeldClient()
        val answered = send(held, 100.milliseconds)
        answered.cancel(true)
        // Past its timeout too: nothing cancels the request.
        Thread.sleep(300)
        held.sent.isCancelled.assert().isFalse()
    }

    @Test
    fun `an answer after the caller gave up should be released`() {
        val held = HeldClient()
        val answered = send(held, 1.seconds)
        answered.cancel(true)
        val response = mockk<TransportHttpClient.Response>(relaxed = true)
        held.sent.complete(response)
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `a synchronous request and the close should be the client's own`() {
        val response = mockk<TransportHttpClient.Response>(relaxed = true)
        val delegate = mockk<TransportHttpClient>(relaxed = true) {
            every { performRequest("get", null, request, null) } returns response
        }
        val client = BoundedHttpClient(delegate, 1.seconds)
        client.performRequest("get", null, request, null).assert().isSameAs(response)
        client.close()
        verify { delegate.close() }
    }

    @Test
    fun `a timeout should be positive`() {
        assertThrownBy<IllegalArgumentException> { BoundedHttpClient(HeldClient(), Duration.ZERO) }
    }

    /**
     * A server that answers every request at once but its second, the first after the client's warm-up: that one it
     * holds without a byte, a paused server, then answers after [holdFor] when it is set. Records when the held request
     * reached it, when it answered it and when the client closed its connection.
     */
    private class HeldServer(private val holdFor: Duration? = null) : AutoCloseable {
        private val server = ServerSocket(0)
        private val requests = AtomicInteger()
        val requested = CompletableFuture<TimeSource.Monotonic.ValueTimeMark>()
        val answeredLate = CompletableFuture<Unit>()
        val closed = CompletableFuture<Unit>()
        val url: String get() = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true) {
                while (true) {
                    val connection = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { serve(connection) }
                }
            }
        }

        private fun serve(connection: Socket) = connection.use {
            val input = it.getInputStream()
            var holding = false
            while (readHead(input)) {
                if (requests.incrementAndGet() == HELD) {
                    holding = true
                    requested.complete(TimeSource.Monotonic.markNow())
                    holdFor ?: break
                    Thread.sleep(holdFor.inWholeMilliseconds)
                    answeredLate.complete(Unit)
                }
                it.getOutputStream().apply {
                    write(OK)
                    flush()
                }
            }
            // The client's end of the connection: closed (end of stream) or reset (aborted).
            runCatching { input.readAllBytes() }
            if (holding) closed.complete(Unit)
        }

        /** Reads one request's head, false at the end of the stream: the requests here have no body. */
        private fun readHead(input: InputStream): Boolean {
            var matched = 0
            while (matched < HEAD_END.length) {
                val byte = runCatching { input.read() }.getOrDefault(-1)
                if (byte < 0) return false
                matched = when (byte.toChar()) {
                    HEAD_END[matched] -> matched + 1
                    '\r' -> 1
                    else -> 0
                }
            }
            return true
        }

        override fun close() = server.close()

        private companion object {
            const val HELD = 2
            const val HEAD_END = "\r\n\r\n"
            val OK = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()
        }
    }

    /** The application's client to [server], one connection in its pool, with library defaults besides [configure]. */
    private fun client(server: HeldServer, configure: (Rest5ClientBuilder) -> Unit = {}): Rest5Client =
        Rest5Client.builder(HttpHost.create(server.url))
            .setConnectionManagerCallback { it.setMaxConnPerRoute(1).setMaxConnTotal(1) }
            .also(configure)
            .build()

    /** A store over [restClient] bounded by [timeout], its client warmed by one request: the held one is not its first. */
    private fun warmStore(restClient: Rest5Client, timeout: Duration): ElasticsearchStore {
        restClient.performRequest(Request("GET", "/warm-up")).statusCode.assert().isEqualTo(OK_STATUS)
        return ElasticsearchStore(
            StoreConfig.of(name = "search", type = ElasticsearchStoreProvider.TYPE),
            restClient,
            ElasticsearchNames(""),
            operationTimeout = timeout,
        )
    }

    /** A request sent now, answered or failed into the future. */
    private fun Rest5Client.next(): CompletableFuture<Int> {
        val outcome = CompletableFuture<Int>()
        performRequestAsync(
            Request("GET", "/next"),
            object : ResponseListener {
                override fun onSuccess(response: Response) {
                    outcome.complete(response.statusCode)
                }

                override fun onFailure(exception: Exception) {
                    outcome.completeExceptionally(exception)
                }
            },
        )
        return outcome
    }

    @Test
    fun `a store's request to a paused server should fail its caller at the timeout, the connection held until it answers`() {
        HeldServer(holdFor = 1500.milliseconds).use { server ->
            client(server).use { restClient ->
                val store = warmStore(restClient, timeout = 300.milliseconds)
                assertThrownBy<StorageUnavailableException> { runBlocking { store.probe() } }
                // Failed within its bound of reaching the server, whatever the client took to send it.
                val reached = server.requested.get(LONG_SECONDS, TimeUnit.SECONDS)
                reached.elapsedNow().assert().isLessThan(1.seconds)
                // The connection is the client's: its pool's only one is still held, so the next request waits for it.
                val next = restClient.next()
                Thread.sleep(300)
                next.isDone.assert().isFalse()
                // The server answers: the answer is released, and the connection serves the next request.
                next.get(LONG_SECONDS, TimeUnit.SECONDS).assert().isEqualTo(OK_STATUS)
                server.answeredLate.isDone.assert().isTrue()
            }
        }
    }

    @Test
    fun `a load its caller gives up on should leave its request running, its connection freed by the answer`() {
        HeldServer(holdFor = 1500.milliseconds).use { server ->
            client(server).use { restClient ->
                val store = warmStore(restClient, timeout = 5.seconds)
                val ref = AggregateType("ctx", "order").ref("tenant", "o-1")
                runBlocking {
                    val load = async(Dispatchers.Default) { store.snapshots("ctx").load(ref) }
                    // The kernel gives the load up once its request is on the wire.
                    server.requested.get(LONG_SECONDS, TimeUnit.SECONDS)
                    load.cancelAndJoin()
                }
                // Not cancelled: the connection stays the request's until the server answers it.
                val next = restClient.next()
                Thread.sleep(300)
                next.isDone.assert().isFalse()
                next.get(LONG_SECONDS, TimeUnit.SECONDS).assert().isEqualTo(OK_STATUS)
                server.closed.isDone.assert().isFalse()
            }
        }
    }

    @Test
    fun `the client's response timeout should free a connection a server never answers`() {
        // The paused server never answers; the application's client bounds its requests itself.
        HeldServer().use { server ->
            client(server) { it.setRequestConfigCallback { config -> config.setResponseTimeout(Timeout.ofSeconds(1)) } }
                .use { restClient ->
                    val store = warmStore(restClient, timeout = 300.milliseconds)
                    assertThrownBy<StorageUnavailableException> { runBlocking { store.probe() } }
                    val reached = server.requested.get(LONG_SECONDS, TimeUnit.SECONDS)
                    // The client ends the exchange at its response timeout, closing the connection, and the pool's
                    // place serves the next request.
                    server.closed.get(LONG_SECONDS, TimeUnit.SECONDS)
                    reached.elapsedNow().assert().isGreaterThan(800.milliseconds)
                    restClient.next().get(LONG_SECONDS, TimeUnit.SECONDS).assert().isEqualTo(OK_STATUS)
                }
        }
    }

    @Test
    fun `the operation timeout should be 30 seconds when not set`() {
        ElasticsearchStoreProvider.OPERATION_TIMEOUT.default.assert().isEqualTo(30.seconds)
        ElasticsearchStoreProvider().options.assert().contains(ElasticsearchStoreProvider.OPERATION_TIMEOUT)
    }

    private companion object {
        const val LONG_SECONDS = 10L
        const val OK_STATUS = 200
    }
}
