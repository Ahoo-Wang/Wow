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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [HttpSignalSender] against the signal route on bare Reactor Netty (design 04 §6.3, decisions D65, D109). */
class HttpSignalSenderTest {
    private val received = CopyOnWriteArrayList<WaitSignal>()
    private val refusing = ClusterChannelHandler { ForwardReply.refused(Refusal.NOT_OWNER) }
    private val server = InternalServer(internalRoutes(refusing, { received += it }))
    private val senders = CopyOnWriteArrayList<HttpSignalSender>()

    private fun sender(
        queueCapacity: Int = HttpSignalSender.DEFAULT_QUEUE_CAPACITY,
        concurrency: Int = HttpSignalSender.DEFAULT_CONCURRENCY,
        idle: Duration = HttpSignalSender.DEFAULT_IDLE_TIMEOUT,
        maxBatch: Int = HttpSignalSender.DEFAULT_MAX_BATCH,
    ) = HttpSignalSender(
        queueCapacity = queueCapacity,
        concurrency = concurrency,
        idleTimeout = idle,
        maxBatch = maxBatch,
    )
        .also { senders += it }

    @AfterEach
    fun close() {
        senders.forEach { it.close() }
        server.close()
    }

    private fun origin(address: String) = Origin("node-1", address, "bank-service")

    private fun signal(n: Int) = WaitSignal(waitId = "w-$n", result = committed("c-$n"))

    @Test
    fun `signals reach the origin's signal entry over HTTP2`(): Unit = runBlocking {
        val sender = sender()
        val signals = (1..20).map(::signal)
        signals.forEach { sender.send(origin(server.address), it, deadline()) }
        eventually { received.size == signals.size }
        received.assert().containsExactlyInAnyOrderElementsOf(signals)
        sender.dropped.assert().isEqualTo(0)
        server.versions.assert().containsOnly("HTTP/2.0")
    }

    @Test
    fun `a signal round-trips exactly over HTTP, its numbers as they were (D122)`(): Unit = runBlocking {
        val sender = sender()
        val signal = WaitSignal(
            waitId = "w-exact",
            result = committed("c-exact").copyWith(
                hop = Hop.handled("order.saga", "order"),
                status = CommandResult.Status.FAILED,
                duplicate = true,
                error = ErrorInfo("bank.account.frozen", "Frozen.", listOf(Violation("amount", "min", "Too low."))),
                results = mapOf(
                    "price" to BigDecimal("2.50"),
                    "precise" to BigDecimal("12345678901234567890.123456789"),
                    "count" to Long.MAX_VALUE,
                    "huge" to BigInteger("123456789012345678901234567890"),
                    "lines" to listOf(mapOf("sku" to "apple", "quantity" to 2)),
                ),
                derived = listOf("c-2", "c-3"),
            ),
        )
        sender.send(origin(server.address), signal, deadline())
        eventually { received.size == 1 }
        received.single().assert().isEqualTo(signal)
        (received.single().result.results["price"] as BigDecimal).scale().assert().isEqualTo(2)
        ClusterCodec.decodeSignal(ClusterCodec.encode(signal)).assert().isEqualTo(signal)
    }

    @Test
    fun `a full queue drops the signal without waiting`(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        val arrived = AtomicInteger()
        InternalServer(
            coRouter {
                POST(InternalHttp.SIGNALS_PATH) { request ->
                    arrived.addAndGet(ClusterCodec.decodeSignals(request.awaitBody<ByteArray>()).size)
                    release.await()
                    ServerResponse.noContent().buildAndAwait()
                }
            },
        ).use { stuck ->
            // One signal a request: a batch drains the queue while the sends refill it, so how many signals a batch
            // takes from a queue of one depends on how the two interleave (review A5-12); the batches have their test.
            val sender = sender(queueCapacity = 1, concurrency = 1, maxBatch = 1)
            val elapsed = measureTimeMillis {
                (1..10).forEach { sender.send(origin(stuck.address), signal(it), deadline()) }
            }
            elapsed.assert().isLessThan(1_000)
            // At most one is in flight, one taken off the queue for the next request and one queued; the rest are
            // dropped at once.
            sender.dropped.assert().isGreaterThanOrEqualTo(7)
            release.complete(Unit)
            eventually { arrived.get() + sender.dropped == 10L }
            arrived.get().assert().isLessThanOrEqualTo(3)
        }
    }

    @Test
    fun `a burst to one origin goes in batches, each a request`(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val signals = CopyOnWriteArrayList<WaitSignal>()
        InternalServer(
            coRouter {
                POST(InternalHttp.SIGNALS_PATH) { request ->
                    requests.incrementAndGet()
                    signals += ClusterCodec.decodeSignals(request.awaitBody<ByteArray>())
                    release.await()
                    ServerResponse.noContent().buildAndAwait()
                }
            },
        ).use { origin ->
            val sender = sender(concurrency = 1)
            sender.send(origin(origin.address), signal(0), deadline())
            eventually { requests.get() == 1 }
            (1..30).forEach { sender.send(origin(origin.address), signal(it), deadline()) }
            release.complete(Unit)
            eventually { signals.size == 31 }
            requests.get().assert().isLessThan(5)
            sender.dropped.assert().isEqualTo(0)
        }
    }

    @Test
    fun `an origin that refuses a batch is sent one signal a request`(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        val singles = CopyOnWriteArrayList<WaitSignal>()
        InternalServer(
            coRouter {
                POST(InternalHttp.SIGNALS_PATH) { request ->
                    release.await()
                    // A node of an earlier version: a single signal only.
                    val signal = runCatching { ClusterCodec.decodeSignal(request.awaitBody<ByteArray>()) }.getOrNull()
                        ?: return@POST ServerResponse.badRequest().buildAndAwait()
                    singles += signal
                    ServerResponse.noContent().buildAndAwait()
                }
            },
        ).use { old ->
            val sender = sender(concurrency = 1)
            (1..6).forEach { sender.send(origin(old.address), signal(it), deadline()) }
            release.complete(Unit)
            eventually { singles.size == 6 }
            singles.map { it.waitId }.assert().containsExactlyInAnyOrder("w-1", "w-2", "w-3", "w-4", "w-5", "w-6")
            sender.dropped.assert().isEqualTo(0)
        }
    }

    @Test
    fun `every dropped signal is observed by its reason`(): Unit = runBlocking {
        val reasons = CopyOnWriteArrayList<String>()
        val observer = object : Observer {
            override fun signalDropped(origin: String, reason: String) {
                reasons += reason
            }
        }
        withRefusingPort { gone ->
            val sender = HttpSignalSender(observer = observer).also { senders += it }
            sender.send(origin("127.0.0.1:$gone"), signal(1), deadline())
            eventually { reasons.size == 1 }
            sender.close()
            sender.send(origin("127.0.0.1:$gone"), signal(2), deadline())
        }
        reasons.assert().containsExactly(HttpSignalSender.FAILED, HttpSignalSender.CLOSED)
    }

    @Test
    fun `a signal the origin cannot take is dropped`(): Unit = runBlocking {
        InternalServer(coRouter { POST(InternalHttp.SIGNALS_PATH) { ServerResponse.status(503).buildAndAwait() } })
            .use { failing ->
                val sender = sender()
                sender.send(origin(failing.address), signal(1), deadline())
                eventually { sender.dropped == 1L }
            }
        withRefusingPort { gone ->
            val sender = sender()
            sender.send(origin("127.0.0.1:$gone"), signal(2), deadline())
            eventually { sender.dropped == 1L }
        }
    }

    @Test
    fun `a signal waits on a lagging origin as long as its wait does`(): Unit = runBlocking {
        // The origin answers after 1.5 s, as one whose event loops lag under load did (roadmap §6 item 1): the
        // wait has 5 s left, so its signal is still worth delivering.
        val taken = CopyOnWriteArrayList<WaitSignal>()
        InternalServer(
            coRouter {
                POST(InternalHttp.SIGNALS_PATH) { request ->
                    val batch = ClusterCodec.decodeSignals(request.awaitBody<ByteArray>())
                    delay(1_500)
                    taken += batch
                    ServerResponse.noContent().buildAndAwait()
                }
            },
        ).use { lagging ->
            val sender = sender()
            sender.send(origin(lagging.address), signal(1), deadline())
            eventually(5.seconds) { taken.size == 1 }
            delay(200)
            sender.dropped.assert().isEqualTo(0)
        }
    }

    @Test
    fun `a signal whose wait is over is dropped unsent`(): Unit = runBlocking {
        val reasons = CopyOnWriteArrayList<String>()
        val sender = HttpSignalSender(
            observer = object : Observer {
                override fun signalDropped(origin: String, reason: String) {
                    reasons += reason
                }
            },
        ).also { senders += it }
        sender.send(origin(server.address), signal(1), System.currentTimeMillis() - 1)
        eventually { reasons.size == 1 }
        reasons.assert().containsExactly(HttpSignalSender.EXPIRED)
        received.assert().isEmpty()
    }

    @Test
    fun `a request is given until its wait's deadline and no longer`(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        InternalServer(
            coRouter {
                POST(InternalHttp.SIGNALS_PATH) {
                    release.await()
                    ServerResponse.noContent().buildAndAwait()
                }
            },
        ).use { stuck ->
            val sender = sender()
            val elapsed = measureTimeMillis {
                sender.send(origin(stuck.address), signal(1), deadline(300.milliseconds))
                eventually(5.seconds) { sender.dropped == 1L }
            }
            elapsed.assert().isLessThan(3_000)
            release.complete(Unit)
        }
    }

    @Test
    fun `an idle origin's queue goes away and a new one takes its next signals`(): Unit = runBlocking {
        val sender = sender(idle = 50.milliseconds)
        sender.send(origin(server.address), signal(1), deadline())
        eventually { received.size == 1 }
        delay(200)
        sender.send(origin(server.address), signal(2), deadline())
        eventually { received.size == 2 }
        sender.dropped.assert().isEqualTo(0)
    }

    @Test
    fun `an origin that answers 503 is sent the signal again, which it then takes twice`(): Unit = runBlocking {
        // Decision D320: the origin took the signal, then answered 503; the retry lands it again, and the origin's
        // wait deduplicates it (WaitRedeliveryTest), so the wait ends once.
        val landed = CopyOnWriteArrayList<WaitSignal>()
        val requests = AtomicInteger()
        InternalServer(
            coRouter {
                POST(InternalHttp.SIGNALS_PATH) { request ->
                    landed += ClusterCodec.decodeSignals(request.awaitBody<ByteArray>())!!
                    ServerResponse.status(if (requests.incrementAndGet() == 1) 503 else 204).buildAndAwait()
                }
            },
        ).use { origin ->
            val sender = HttpSignalSender(retryBackoff = 10.milliseconds).also { senders += it }
            sender.send(origin(origin.address), signal(1), deadline())
            eventually { landed.size == 2 }
            landed.assert().containsExactly(signal(1), signal(1))
            delay(100)
            requests.get().assert().isEqualTo(2)
            sender.dropped.assert().isZero()
        }
    }

    @Test
    fun `a refused connection is tried again until the origin is up`(): Unit = runBlocking {
        // A node being replaced: its port refuses connections, then the new one takes the signal.
        val port = withRefusingPort { it }
        val sender = HttpSignalSender(retryBackoff = 100.milliseconds, maxAttempts = 10).also { senders += it }
        sender.send(origin("127.0.0.1:$port"), signal(1), deadline())
        delay(150)
        InternalServer(internalRoutes(refusing, { received += it }), port = port).use {
            eventually { received.size == 1 }
        }
        received.single().assert().isEqualTo(signal(1))
        sender.dropped.assert().isZero()
    }

    @Test
    fun `a request is sent at most its attempts, and a definite answer once`(): Unit = runBlocking {
        val reasons = CopyOnWriteArrayList<String>()
        val observer = object : Observer {
            override fun signalDropped(origin: String, reason: String) {
                reasons += reason
            }
        }
        val requests = AtomicInteger()
        var status = 503
        InternalServer(
            coRouter {
                POST(InternalHttp.SIGNALS_PATH) {
                    requests.incrementAndGet()
                    ServerResponse.status(status).buildAndAwait()
                }
            },
        ).use { origin ->
            val sender = HttpSignalSender(observer = observer, retryBackoff = 10.milliseconds, maxAttempts = 3)
                .also { senders += it }
            sender.send(origin(origin.address), signal(1), deadline())
            eventually { sender.dropped == 1L }
            requests.get().assert().isEqualTo(3)
            status = 404
            sender.send(origin(origin.address), signal(2), deadline())
            eventually { sender.dropped == 2L }
            requests.get().assert().isEqualTo(4)
            // A lone signal refused as malformed has nothing to split: it is dropped, not sent again.
            status = 400
            sender.send(origin(origin.address), signal(3), deadline())
            eventually { sender.dropped == 3L }
            requests.get().assert().isEqualTo(5)
        }
        reasons.assert().containsExactly(HttpSignalSender.REFUSED, HttpSignalSender.REFUSED, HttpSignalSender.REFUSED)
    }

    @Test
    fun `a failing request is never sent again past its wait's deadline`(): Unit = runBlocking {
        withRefusingPort { gone ->
            val sender = HttpSignalSender(retryBackoff = 100.milliseconds, maxAttempts = 100).also { senders += it }
            val elapsed = measureTimeMillis {
                sender.send(origin("127.0.0.1:$gone"), signal(1), deadline(500.milliseconds))
                eventually { sender.dropped == 1L }
            }
            elapsed.assert().isLessThan(1_500)
        }
    }

    @Test
    fun `a failure that passes is told from one that does not`() {
        java.io.IOException(java.nio.channels.UnresolvedAddressException()).isTransient().assert().isTrue()
        java.net.ConnectException("refused").isTransient().assert().isTrue()
        java.net.UnknownHostException("node-2").isTransient().assert().isTrue()
        java.net.http.HttpConnectTimeoutException("connect").isTransient().assert().isTrue()
        java.net.http.HttpTimeoutException("request").isTransient().assert().isFalse()
        IllegalStateException("bug").isTransient().assert().isFalse()
    }

    @Test
    fun `a closed sender drops what it is given`(): Unit = runBlocking {
        val sender = sender()
        sender.close()
        sender.send(origin(server.address), signal(1), deadline())
        sender.dropped.assert().isEqualTo(1)
        received.assert().isEmpty()
    }
}
