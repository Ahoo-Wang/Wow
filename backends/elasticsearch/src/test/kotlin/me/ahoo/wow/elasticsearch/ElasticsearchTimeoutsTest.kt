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
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
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
import me.ahoo.wow.spi.store.StoreResources
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
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
    fun `a request with no answer within the timeout should fail as an I-O timeout and be cancelled`() {
        val held = HeldClient()
        val started = TimeSource.Monotonic.markNow()
        val failure = assertThrows<ExecutionException> { send(held, 100.milliseconds).get(5, TimeUnit.SECONDS) }
        started.elapsedNow().assert().isLessThan(2.seconds)
        failure.cause.assert().isInstanceOf(SocketTimeoutException::class.java)
        failure.cause!!.message.assert().isEqualTo("Elasticsearch gave no answer to get within 100ms.")
        held.sent.isCancelled.assert().isTrue()
    }

    @Test
    fun `an answer in time should be the request's, and its deadline should not fire after it`() {
        val held = HeldClient()
        val response = mockk<TransportHttpClient.Response>(relaxed = true)
        val answered = send(held, 300.milliseconds)
        held.sent.complete(response)
        answered.get(1, TimeUnit.SECONDS).assert().isSameAs(response)
        Thread.sleep(500)
        held.sent.isCancelled.assert().isFalse()
        verify(exactly = 0) { response.close() }
    }

    @Test
    fun `an answer after the timeout should be released`() {
        // A client whose request cannot be cancelled any more: its answer was already on the way.
        val held = object : HeldClient() {
            override val sent = object : CompletableFuture<TransportHttpClient.Response>() {
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
            }
        }
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
    fun `a caller giving up should end its wait alone, the request left to its deadline`() {
        val held = HeldClient()
        val answered = send(held, 200.milliseconds)
        answered.cancel(true)
        // Not cancelled by the caller: a request cancelled before its bytes are written is retried by HttpClient 5.
        held.sent.isCancelled.assert().isFalse()
        // Its deadline still aborts it.
        val started = TimeSource.Monotonic.markNow()
        while (!held.sent.isCancelled) {
            check(started.elapsedNow() < 2.seconds) { "The deadline did not cancel the request." }
            Thread.sleep(10)
        }
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

    @Test
    fun `a store's request to a server that accepts and never answers should fail within its operation timeout`() {
        ServerSocket(0).use { server ->
            // Accepts, then holds every connection without a byte: a paused server. Records when the request reached it
            // and when the client lets go.
            val requested = CompletableFuture<TimeSource.Monotonic.ValueTimeMark>()
            val released = CompletableFuture<Unit>()
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    if (input.read() >= 0) requested.complete(TimeSource.Monotonic.markNow())
                    // The client's end of the connection: closed (end of stream) or reset (aborted).
                    runCatching { input.readAllBytes() }
                    released.complete(Unit)
                }
            }
            // The application's client has no response timeout of its own.
            val restClient = Rest5Client.builder(HttpHost.create("http://127.0.0.1:${server.localPort}")).build()
            restClient.use {
                val store = ElasticsearchStoreProvider().open(
                    StoreConfig.of(
                        name = "search",
                        type = ElasticsearchStoreProvider.TYPE,
                        options = mapOf(ElasticsearchStoreProvider.OPERATION_TIMEOUT.key to "PT0.3S"),
                        resources = StoreResources.of(mapOf("elasticsearch" to restClient)),
                    ),
                ) as ElasticsearchStore
                assertThrownBy<StorageUnavailableException> { runBlocking { store.probe() } }
                // Within its bound of the request reaching the server, whatever a cold client took to send it.
                requested.get(LONG_SECONDS, TimeUnit.SECONDS).elapsedNow().assert().isLessThan(3.seconds)
                // The exchange is aborted: the connection is closed, not left to the client's pool to wait on.
                released.get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `a load its caller gives up on should keep its connection until the operation timeout aborts it`() {
        ServerSocket(0).use { server ->
            // Accepts, reads the request's first bytes, then holds the connection without answering: a paused server.
            val requested = CompletableFuture<Unit>()
            val released = CompletableFuture<Unit>()
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    if (input.read() >= 0) requested.complete(Unit)
                    // The client's end of the connection: closed (end of stream) or reset (aborted).
                    runCatching { input.readAllBytes() }
                    released.complete(Unit)
                }
            }
            val restClient = Rest5Client.builder(HttpHost.create("http://127.0.0.1:${server.localPort}")).build()
            restClient.use {
                val store = ElasticsearchStoreProvider().open(
                    StoreConfig.of(
                        name = "search",
                        type = ElasticsearchStoreProvider.TYPE,
                        options = mapOf(ElasticsearchStoreProvider.OPERATION_TIMEOUT.key to "PT2S"),
                        resources = StoreResources.of(mapOf("elasticsearch" to restClient)),
                    ),
                ) as ElasticsearchStore
                val ref = AggregateType("ctx", "order").ref("tenant", "o-1")
                runBlocking {
                    val load = async(Dispatchers.Default) { store.snapshots("ctx").load(ref) }
                    // The kernel gives the load up once its request is on the wire.
                    requested.get(LONG_SECONDS, TimeUnit.SECONDS)
                    val gaveUp = TimeSource.Monotonic.markNow()
                    load.cancelAndJoin()
                    // The caller's wait has ended; the request's connection is still open, under its deadline.
                    Thread.sleep(500)
                    released.isDone.assert().isFalse()
                    // The store's operation timeout aborts it, closing its connection.
                    released.get(LONG_SECONDS, TimeUnit.SECONDS)
                    gaveUp.elapsedNow().assert().isLessThan(LONG_SECONDS.seconds)
                }
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
    }
}
