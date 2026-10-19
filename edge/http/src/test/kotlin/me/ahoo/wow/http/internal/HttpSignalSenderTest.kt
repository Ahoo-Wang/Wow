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
        signals.forEach { sender.send(origin(server.address), it) }
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
        sender.send(origin(server.address), signal)
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
                (1..10).forEach { sender.send(origin(stuck.address), signal(it)) }
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
            sender.send(origin(origin.address), signal(0))
            eventually { requests.get() == 1 }
            (1..30).forEach { sender.send(origin(origin.address), signal(it)) }
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
            (1..6).forEach { sender.send(origin(old.address), signal(it)) }
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
            sender.send(origin("127.0.0.1:$gone"), signal(1))
            eventually { reasons.size == 1 }
            sender.close()
            sender.send(origin("127.0.0.1:$gone"), signal(2))
        }
        reasons.assert().containsExactly(HttpSignalSender.FAILED, HttpSignalSender.CLOSED)
    }

    @Test
    fun `a signal the origin cannot take is dropped`(): Unit = runBlocking {
        InternalServer(coRouter { POST(InternalHttp.SIGNALS_PATH) { ServerResponse.status(503).buildAndAwait() } })
            .use { failing ->
                val sender = sender()
                sender.send(origin(failing.address), signal(1))
                eventually { sender.dropped == 1L }
            }
        withRefusingPort { gone ->
            val sender = sender()
            sender.send(origin("127.0.0.1:$gone"), signal(2))
            eventually { sender.dropped == 1L }
        }
    }

    @Test
    fun `an idle origin's queue goes away and a new one takes its next signals`(): Unit = runBlocking {
        val sender = sender(idle = 50.milliseconds)
        sender.send(origin(server.address), signal(1))
        eventually { received.size == 1 }
        delay(200)
        sender.send(origin(server.address), signal(2))
        eventually { received.size == 2 }
        sender.dropped.assert().isEqualTo(0)
    }

    @Test
    fun `a closed sender drops what it is given`(): Unit = runBlocking {
        val sender = sender()
        sender.close()
        sender.send(origin(server.address), signal(1))
        sender.dropped.assert().isEqualTo(1)
        received.assert().isEmpty()
    }
}
