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
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.runtime.cluster.Refusal
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import java.net.ServerSocket
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
    ) = HttpSignalSender(queueCapacity = queueCapacity, concurrency = concurrency, idleTimeout = idle)
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
    fun `a full queue drops the signal without waiting`(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        val arrived = AtomicInteger()
        InternalServer(
            coRouter {
                POST(InternalHttp.SIGNALS_PATH) {
                    arrived.incrementAndGet()
                    release.await()
                    ServerResponse.noContent().buildAndAwait()
                }
            },
        ).use { stuck ->
            val sender = sender(queueCapacity = 1, concurrency = 1)
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
    fun `a signal the origin cannot take is dropped`(): Unit = runBlocking {
        InternalServer(coRouter { POST(InternalHttp.SIGNALS_PATH) { ServerResponse.status(503).buildAndAwait() } })
            .use { failing ->
                val sender = sender()
                sender.send(origin(failing.address), signal(1))
                eventually { sender.dropped == 1L }
            }
        val gone = ServerSocket(0).use { it.localPort }
        val sender = sender()
        sender.send(origin("127.0.0.1:$gone"), signal(2))
        eventually { sender.dropped == 1L }
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
