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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * An attempt whose connection is not made within the request's bound (#6993). The JDK's own request timeout, which
 * the transport sets to the bound, says whether the connection was made, and is due at the same instant as the
 * transport's own bound: until the response's head, the JDK's verdict decides, so the request fails over as a refused
 * one does, whichever of the two timers fires first. Here the JDK's timer is played by [Unmade], at an offset from the
 * request timeout that a test sets: early, late within the grace, or late past it, as a loaded machine makes it.
 */
class ConnectNotMadeTest {
    private val peers = CopyOnWriteArrayList<FakePeer>()

    @AfterEach
    fun close() {
        peers.forEach(FakePeer::close)
    }

    private fun peer(vararg replies: Reply) = FakePeer().reply(*replies).also { peers += it }

    private val events = CopyOnWriteArrayList<ObservedEvent>()
    private val silent = SilentPeers(
        object : Observer {
            override fun observed(event: ObservedEvent) {
                events += event
            }
        },
        30.seconds,
        AtomicLong(0)::get,
    )

    @Volatile
    private var listed: List<URI> = emptyList()

    private val endpoints = object : EndpointResolver {
        override suspend fun resolve(context: String, port: Port): URI? = listed.firstOrNull()

        override suspend fun resolveAll(context: String, port: Port): List<URI> = PeerList(listed, 1)
    }

    /** A node whose connection is never made: nothing listens behind [Unmade]'s address for it. */
    private val unmade = URI.create("http://127.0.0.1:1")

    private fun transport(client: HttpClient) =
        HttpTransport(endpoints, ClientMode.Service("basket-service"), client, BOUND, RetryPolicy.NONE, silent = silent)

    private fun HttpTransport.send(): Answer<ByteArray> = runBlocking {
        exchange(
            PeerRequest("mail", "/mail/box/x", Caller.ANONYMOUS, null, REQUEST_ID, "{}".toByteArray()),
            Exchange.COMMAND,
        )
    }

    private fun Answer<*>.code(): String? = (this as? Answer.Problem)?.problem?.code

    @Test
    fun `a connect not made within the bound should fail over, though the JDK says so after the bound passed`() {
        val live = peer(Reply(200))
        listed = listOf(unmade, live.base)
        val client = Unmade(unmade, late = 150.milliseconds)

        val answer = transport(client).send()

        answer.assert().describedAs { "${answer.code()}, bypassed ${silent.bypassed("mail")}" }
            .isInstanceOf(Answer.Ok::class.java)
        client.attempts.get().assert().describedAs("attempts at the node whose connection is not made").isEqualTo(1)
        live.taken.assert().hasSize(1)
        silent.bypassed("mail").assert().describedAs("nothing was sent: no node went silent").isEmpty()
        events.assert().isEmpty()
    }

    @Test
    fun `a connect not made within the bound should fail over when the JDK says so first`() {
        val live = peer(Reply(200))
        listed = listOf(unmade, live.base)

        transport(Unmade(unmade, late = (-100).milliseconds)).send().assert().isInstanceOf(Answer.Ok::class.java)
        silent.bypassed("mail").assert().isEmpty()
    }

    @Test
    fun `without the JDK's word within the grace, the attempt should end as sent and not answered, as before`() {
        // Unsent only on positive knowledge: a verdict later than the grace is no verdict.
        val live = peer(Reply(200))
        listed = listOf(unmade, live.base)
        val started = TimeSource.Monotonic.markNow()

        val answer = transport(Unmade(unmade, late = 3.seconds)).send()

        answer.code().assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        silent.bypassed("mail").assert().containsExactly(unmade.toString())
        live.taken.assert().isEmpty()
        started.elapsedNow().assert().describedAs("the bound and at most the grace").isLessThan(BOUND + 2.seconds)
    }

    @Test
    fun `a connection made and not answered should end as sent and not answered, as before`() {
        // The JDK's own word for a request sent and not answered within its timeout: HttpTimeoutException.
        val live = peer(Reply(200))
        listed = listOf(unmade, live.base)

        val answer = transport(Unmade(unmade, late = 150.milliseconds, connected = true)).send()

        answer.code().assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        silent.bypassed("mail").assert().containsExactly(unmade.toString())
        live.taken.assert().isEmpty()
    }

    @Test
    fun `a body slow after the response's head should end at the bound, as before, without waiting for the JDK`() {
        // The JDK's timer no longer runs once the head is in: the transport's bound alone ends the body.
        val slow = peer(Reply(200, chunks = listOf("{"), holdAfterChunks = true))
        listed = listOf(slow.base)
        val started = TimeSource.Monotonic.markNow()

        val answer = transport(HttpCommandCaller.client()).send()

        answer.code().assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        started.elapsedNow().assert().describedAs("no grace after the head").isLessThan(BOUND + 700.milliseconds)
        silent.bypassed("mail").assert().containsExactly(slow.base.toString().trimEnd('/'))
    }

    @Test
    fun `a command a node did take, though the JDK said otherwise, should be sent again under its request id`() {
        // The consequence of a wrong "unsent": the resend carries the request id, so the partition's owner decides the
        // command once and answers the second arrival as a duplicate (:runtime's ClusterTest, "an owner deciding
        // longer than the forward timeout decides the request exactly once"; the kernel's duplicates by request id,
        // KernelRecentRequestsTest).
        val took = peer(Reply.HELD)
        val live = peer(Reply(200))
        listed = listOf(took.base, live.base)
        val client = Unmade(took.base, late = 150.milliseconds, deliverTo = HttpCommandCaller.client())

        transport(client).send().assert().isInstanceOf(Answer.Ok::class.java)

        took.taken.map { it.headers["Wow-Request-Id"] }.assert().containsExactly("r-1")
        live.taken.map { it.headers["Wow-Request-Id"] }.assert().containsExactly("r-1")
    }

    /**
     * An [HttpClient] whose exchanges with [pending] never make their connection: each ends as the JDK ends one at
     * its request timeout, [late] after that timeout, with [HttpConnectTimeoutException], or, when [connected],
     * with [HttpTimeoutException], the JDK's word for a request sent and not answered. With [deliverTo], the request
     * is delivered to [pending] first, though the verdict says it was not: a wrong "unsent". Every other target goes
     * to [delegate].
     */
    private class Unmade(
        private val pending: URI,
        private val late: Duration,
        private val connected: Boolean = false,
        private val deliverTo: HttpClient? = null,
        private val delegate: HttpClient = HttpCommandCaller.client(),
    ) : HttpClient() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            if (request.uri().port != pending.port) return delegate.sendAsync(request, handler)
            attempts.incrementAndGet()
            deliverTo?.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            val verdict = CompletableFuture<HttpResponse<T>>()
            val after = request.timeout().orElseThrow().toMillis() + late.inWholeMilliseconds
            CompletableFuture.delayedExecutor(after.coerceAtLeast(0), TimeUnit.MILLISECONDS).execute {
                val failure = if (connected) {
                    HttpTimeoutException("request timed out")
                } else {
                    HttpConnectTimeoutException("HTTP connect timed out")
                }
                verdict.completeExceptionally(failure)
            }
            return verdict
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, handler)

        override fun <T : Any?> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
            sendAsync(request, handler).get()

        override fun cookieHandler(): Optional<CookieHandler> = delegate.cookieHandler()

        override fun connectTimeout(): Optional<java.time.Duration> = delegate.connectTimeout()

        override fun followRedirects(): Redirect = delegate.followRedirects()

        override fun proxy(): Optional<ProxySelector> = delegate.proxy()

        override fun sslContext(): SSLContext = delegate.sslContext()

        override fun sslParameters(): SSLParameters = delegate.sslParameters()

        override fun authenticator(): Optional<Authenticator> = delegate.authenticator()

        override fun version(): Version = delegate.version()

        override fun executor(): Optional<Executor> = delegate.executor()
    }

    private companion object {
        val BOUND = 300.milliseconds
        val REQUEST_ID = mapOf("Wow-Request-Id" to "r-1")
    }
}
