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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.runtime.cluster.ClusterChannelException
import me.ahoo.wow.runtime.cluster.ClusterChannelHandler
import me.ahoo.wow.runtime.cluster.ClusterStream
import me.ahoo.wow.runtime.cluster.ForwardReply
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A node's forwards and signals on its one HTTP/2 connection to a peer, more of them at once than the peer's streams
 * (review X-55): none fails or is dropped for the peer's stream limit, and forwards at their deadline do not reset
 * their streams, which made the peer's server close the connection under every forward on it; they wait the grace
 * for the owner's answer (review X-61).
 */
class InternalStreamsTest {
    private val client = InternalHttp.client()
    private val signals = CopyOnWriteArrayList<WaitSignal>()
    private val servers = CopyOnWriteArrayList<AutoCloseable>()
    private val senders = CopyOnWriteArrayList<HttpSignalSender>()
    private val release = CompletableDeferred<Unit>()

    /** The commands the owner executed, the forwards it is executing now, and the most it executed at once. */
    private val executed = CopyOnWriteArrayList<String>()
    private val executing = AtomicInteger()
    private val mostExecuting = AtomicInteger()

    /**
     * The owner: a command of context `slow` waits for [release]; one of `late` is answered just after its forward's
     * deadline, as an owner answers a command it withdrew at its wait's deadline; any other after a little work.
     */
    private val owner = ClusterChannelHandler { request ->
        executed += request.command.id
        val now = executing.incrementAndGet()
        mostExecuting.accumulateAndGet(now, ::maxOf)
        try {
            when (request.command.ref.type.context) {
                "slow" -> release.await()
                "late" -> delay(request.deadline - System.currentTimeMillis() + ANSWER_LAG)
                else -> delay(WORK)
            }
            ForwardReply(result = committed(request.command.id, request.command.ref.type.context))
        } finally {
            executing.decrementAndGet()
        }
    }

    private fun server(streams: Int = InternalHttp.MAX_CONCURRENT_STREAMS): InternalServer =
        InternalServer(internalRoutes(owner, { signals += it }), streams = streams).also { servers += it }

    private fun sender(maxBatch: Int = HttpSignalSenderOptions.DEFAULT_MAX_BATCH): HttpSignalSender =
        HttpSignalSender(client, options = HttpSignalSenderOptions(maxBatch = maxBatch)).also { senders += it }

    @AfterEach
    fun close() {
        release.complete(Unit)
        senders.forEach { it.close() }
        servers.forEach { it.close() }
    }

    @Test
    fun `forwards and signals past the internal port's streams wait for one, none failing or dropped`(): Unit =
        runBlocking {
            val port = InternalPort.of(
                InternalPortSettings(port = 0, bindHost = "127.0.0.1"),
                internalRoutes(owner, { signals += it }),
            )
            port.start()
            servers += AutoCloseable { port.stop() }
            val address = "127.0.0.1:${port.boundPort}"
            val channel = HttpClusterChannel(client)
            // A request a signal, so the signals take as many streams as they may.
            val sender = sender(maxBatch = 1)
            channel.connect(address)
            val forwards = (1..FORWARDS).map { n -> async { channel.forward(address, forwardCommand("c-$n")) } }
            val origin = Origin("node-1", address, "bank-service")
            repeat(SIGNALS) { n ->
                sender.send(origin, WaitSignal(waitId = "w-$n", result = committed("c-$n")), deadline())
            }
            forwards.awaitAll().map { it.result!!.commandId }.assert()
                .containsExactlyInAnyOrderElementsOf((1..FORWARDS).map { "c-$it" })
            eventually { signals.size == SIGNALS }
            sender.dropped.assert().isEqualTo(0)
            // The forwards kept to their share of the peer's streams, leaving the signals theirs.
            mostExecuting.get().assert().isGreaterThan(1).isLessThanOrEqualTo(HttpClusterChannel.DEFAULT_STREAMS)
        }

    @Test
    fun `a forward the JDK refuses for the peer's limit is sent once one of its streams is free`(): Unit =
        runBlocking {
            val server = server(streams = 4)
            val channel = HttpClusterChannel(client)
            channel.connect(server.address)
            (1..12).map { n -> async { channel.forward(server.address, forwardCommand("c-$n")) } }
                .awaitAll().map { it.result!!.commandId }.assert().hasSize(12)
            mostExecuting.get().assert().isLessThanOrEqualTo(4)
            server.connections.get().assert().isEqualTo(1)
        }

    @Test
    fun `a forward the peer's limit keeps from being sent by its deadline fails unsent`(): Unit = runBlocking {
        val server = server(streams = 1)
        val channel = HttpClusterChannel(client)
        channel.connect(server.address)
        val held = async { channel.forward(server.address, forwardCommand("held", context = "slow")) }
        eventually { executing.get() == 1 }
        val failure = failsWith<ClusterChannelException> {
            channel.forward(server.address, forwardCommand("c-1", deadline = System.currentTimeMillis() + 200))
        }
        // It never was sent; the router, past the forward's deadline, runs it nowhere (X-34, X-61).
        failure.unsent.assert().isTrue()
        failure.message.assert().contains("it was not sent")
        release.complete(Unit)
        held.await().result!!.commandId.assert().isEqualTo("held")
        executed.assert().containsExactly("held")
    }

    @Test
    fun `a forward reserves its stream before it is sent, and one that got it only past its deadline is not sent`(): Unit =
        runBlocking {
            val server = server()
            val channel = HttpClusterChannel(client, streams = 1)
            channel.connect(server.address)
            val held = async { channel.forward(server.address, forwardCommand("held", context = "slow")) }
            eventually { executing.get() == 1 }
            // As the router bounds a reservation, at the forward's deadline: none taken, nothing sent (review X-61).
            var reserved: ClusterStream? = null
            withTimeoutOrNull(200.milliseconds) { reserved = channel.reserve(server.address) }.assert().isNull()
            reserved.assert().isNull()
            // A caller with no bound of its own: the stream comes after the deadline, and the forward is not sent.
            val unbounded = async {
                val request = forwardCommand("unbounded", deadline = System.currentTimeMillis() + 200)
                failsWith<ClusterChannelException> { channel.forward(server.address, request) }
            }
            delay(400)
            unbounded.isCompleted.assert().isFalse()
            release.complete(Unit)
            unbounded.await().unsent.assert().isTrue()
            held.await().result!!.commandId.assert().isEqualTo("held")
            // A stream released unused is free again, and carries no forward after.
            val unused = channel.reserve(server.address)
            unused.release()
            unused.release()
            assertThrownBy<IllegalStateException> { runBlocking { unused.forward(forwardCommand("unused")) } }
            val next = channel.reserve(server.address)
            next.forward(forwardCommand("next")).result!!.commandId.assert().isEqualTo("next")
            executed.assert().containsExactly("held", "next")
        }

    @Test
    fun `a connect to a peer whose streams are all taken finds it connected`(): Unit = runBlocking {
        val server = server(streams = 2)
        val busy = HttpClusterChannel(client, streams = 2)
        busy.connect(server.address)
        val held = (1..2).map { n ->
            async { busy.forward(server.address, forwardCommand("held-$n", context = "slow")) }
        }
        eventually { executing.get() == 2 }
        // Another channel on the same client: its first connect to the peer is a request on the busy connection.
        HttpClusterChannel(client).connect(server.address)
        release.complete(Unit)
        held.awaitAll().assert().hasSize(2)
    }

    @Test
    fun `a signal the connection's streams leave no room for is sent again once one is free`(): Unit = runBlocking {
        val server = server(streams = 2)
        val channel = HttpClusterChannel(client, streams = 2)
        channel.connect(server.address)
        val held = (1..2).map { n ->
            async { channel.forward(server.address, forwardCommand("held-$n", context = "slow")) }
        }
        eventually { executing.get() == 2 }
        val sender = sender()
        val origin = Origin("node-1", server.address, "bank-service")
        sender.send(origin, WaitSignal(waitId = "w-1", result = committed("c-1")), deadline())
        delay(HttpSignalSenderOptions.DEFAULT_RETRY_BACKOFF * 2)
        signals.assert().isEmpty()
        release.complete(Unit)
        eventually { signals.size == 1 }
        sender.dropped.assert().isEqualTo(0)
        held.awaitAll().assert().hasSize(2)
    }

    @Test
    fun `forwards the owner answers just after their deadline get that answer and leave the connection open`(): Unit =
        runBlocking {
            val server = server()
            val channel = HttpClusterChannel(client)
            channel.connect(server.address)
            val inFlight = async { channel.forward(server.address, forwardCommand("in-flight", context = "slow")) }
            eventually { executing.get() == 1 }
            // More of them than the 200 resets in 30 seconds after which the peer's server closes the connection, were
            // each reset at its deadline; in waves that each find their streams free, so every one of them is sent.
            val answered = (1..ABANDONED).chunked(WAVE).flatMap { wave ->
                wave.map { n ->
                    async {
                        val deadline = System.currentTimeMillis() + ABANDON_AFTER.inWholeMilliseconds
                        val request = forwardCommand("late-$n", context = "late", deadline = deadline)
                        channel.forward(server.address, request)
                    }
                }.awaitAll()
            }
            // The owner's answer, within the grace, is each one's outcome (review X-61), and no stream was reset.
            answered.map { it.result!!.commandId }.assert().containsExactlyElementsOf((1..ABANDONED).map { "late-$it" })
            release.complete(Unit)
            inFlight.await().result!!.commandId.assert().isEqualTo("in-flight")
            channel.forward(server.address, forwardCommand("after")).result!!.commandId.assert().isEqualTo("after")
            // One connection served them all: it was never closed under them.
            server.connections.get().assert().isEqualTo(1)
        }

    @Test
    fun `a stream stays taken past its forward's deadline until the owner answers or the grace ends`(): Unit =
        runBlocking {
            val server = server()
            val grace = 400.milliseconds
            val channel = HttpClusterChannel(client, streams = 1, answerGrace = grace)
            channel.connect(server.address)
            val start = System.currentTimeMillis()
            val stuck = TimeSource.Monotonic.markNow()
            // An owner that does not answer: the channel waits the grace past the deadline, then fails, not unsent.
            failsWith<ClusterChannelException> {
                channel.forward(server.address, forwardCommand("stuck", context = "slow", deadline = start + 100))
            }.unsent.assert().isFalse()
            // At least the grace, and no tighter: the deadline is a wall-clock millisecond the channel turns into whole
            // milliseconds left, then waits on monotonic time, so the part up to the deadline can come out just under
            // 100 ms. Waiting the grace past the deadline takes about 500 ms; giving up at it, about 100.
            stuck.elapsedNow().assert().isGreaterThanOrEqualTo(grace)
            // A caller that gives up at the deadline: the stream is the forward's until its grace ends.
            val abandoned = System.currentTimeMillis()
            val sent = TimeSource.Monotonic.markNow()
            val given = forwardCommand("abandoned", context = "slow", deadline = abandoned + 100)
            withTimeoutOrNull(100.milliseconds) { channel.forward(server.address, given) }.assert().isNull()
            val waiting = forwardCommand("c-1", deadline = System.currentTimeMillis() + 100)
            withTimeoutOrNull(100.milliseconds) { channel.forward(server.address, waiting) }.assert().isNull()
            val waited = measureTimeMillis {
                channel.forward(server.address, forwardCommand("c-2", deadline = System.currentTimeMillis() + 5_000))
                    .result!!.commandId.assert().isEqualTo("c-2")
            }
            // At least the grace, as above: the stream came free about 500 ms after the abandoned forward was sent.
            sent.elapsedNow().assert().isGreaterThanOrEqualTo(grace)
            waited.assert().isLessThan(5_000)
        }

    @Test
    fun `a channel takes at least one stream to a peer, and a grace that is never negative`() {
        assertThrownBy<IllegalArgumentException> { HttpClusterChannel(client, streams = 0) }
        assertThrownBy<IllegalArgumentException> { HttpClusterChannel(client, answerGrace = (-1).milliseconds) }
        HttpClusterChannel(client, streams = 1, answerGrace = Duration.ZERO)
    }

    private companion object {
        const val FORWARDS = 300
        const val SIGNALS = 200
        const val ABANDONED = 250
        val ABANDON_AFTER = 100.milliseconds
        const val ANSWER_LAG = 20L
        const val WAVE = 50
        val WORK = 20.milliseconds
    }
}
