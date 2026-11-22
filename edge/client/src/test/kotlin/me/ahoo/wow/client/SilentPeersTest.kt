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
import me.ahoo.wow.spi.observation.ObservedEvent.PeerRestored.Reason
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A node that took a request and did not answer it within its bound is passed over by the requests that follow
 * (#6519): it costs one request its bound, not every request that rotates onto it until the list is read again. The
 * silent node here takes each request and never answers it ([Reply.HELD]), as a node gone without closing its
 * connections does; the request's bound is [BOUND].
 */
class SilentPeersTest {
    private val peers = CopyOnWriteArrayList<FakePeer>()

    @AfterEach
    fun close() {
        peers.forEach(FakePeer::close)
    }

    private fun peer(vararg replies: Reply) = FakePeer().reply(*replies).also { peers += it }

    private val events = CopyOnWriteArrayList<ObservedEvent>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            events += event
        }
    }

    /** The monotonic time of the bypasses, in nanoseconds, which a test moves on. */
    private val now = AtomicLong(0)

    private val silent = SilentPeers(observer, LAPSE, now::get)

    /** The list a lookup of `mail` finds now, and its generation, which a test changes. */
    @Volatile
    private var listed: List<URI> = emptyList()

    @Volatile
    private var generation: Long? = 1

    private val endpoints = object : EndpointResolver {
        override suspend fun resolve(context: String, port: Port): URI? = listed.firstOrNull()

        override suspend fun resolveAll(context: String, port: Port): List<URI> =
            generation?.let { PeerList(listed, it) } ?: listed
    }

    private fun transport() = HttpTransport(
        endpoints,
        ClientMode.Service("basket-service"),
        HttpCommandCaller.client(),
        BOUND,
        RetryPolicy.NONE,
        silent = silent,
    )

    private fun HttpTransport.send(kind: Exchange = Exchange.COMMAND): Answer<ByteArray> = runBlocking {
        exchange(PeerRequest("mail", "/mail/box/x", Caller.ANONYMOUS, null), kind)
    }

    private fun Answer<*>.code(): String? = (this as? Answer.Problem)?.problem?.code

    private fun keyOf(peer: FakePeer) = peer.base.toString().trimEnd('/')

    @Test
    fun `a node that never answers should cost only the first request its bound, the next going to the others`() {
        val held = peer(Reply.HELD)
        val live = peer(Reply(200))
        listed = listOf(held.base, live.base)
        val transport = transport()
        // The first request rotates onto the silent node and is not answered: its outcome stays unknown.
        transport.send().code().assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        val started = TimeSource.Monotonic.markNow()
        repeat(4) { transport.send().assert().isInstanceOf(Answer.Ok::class.java) }
        // None of the next four went to the silent node, so none paid its bound.
        started.elapsedNow().assert().isLessThan(BOUND * 2)
        held.taken.assert().hasSize(1)
        live.taken.assert().hasSize(4)
        silent.bypassed("mail").assert().containsExactly(keyOf(held))
        events.assert().containsExactly(ObservedEvent.PeerBypassed("mail", keyOf(held)))
    }

    @Test
    fun `a list read again without the node should remove it`() {
        val held = peer(Reply.HELD)
        val live = peer(Reply(200))
        listed = listOf(held.base, live.base)
        val transport = transport()
        transport.send()
        listed = listOf(live.base)
        generation = 2
        transport.send().assert().isInstanceOf(Answer.Ok::class.java)
        silent.bypassed("mail").assert().isEmpty()
        events.last().assert().isEqualTo(ObservedEvent.PeerRestored("mail", keyOf(held), 0, Reason.UNLISTED))
    }

    @Test
    fun `a list read again with the node should bring it back, and a silent one bypassed again for one request`() {
        val held = peer(Reply.HELD)
        val live = peer(Reply(200))
        listed = listOf(held.base, live.base)
        val transport = transport()
        transport.send()
        transport.send().assert().isInstanceOf(Answer.Ok::class.java)
        generation = 2
        now.addAndGet(5.seconds.inWholeNanoseconds)
        // The list read again lists it: it is back in the rotation, and the next request may go to it.
        val answers = List(2) { transport.send() }
        events.assert().contains(ObservedEvent.PeerRestored("mail", keyOf(held), 5_000, Reason.RELISTED))
        // Still silent, it took one more request, which paid its bound, and is bypassed again.
        held.taken.assert().hasSize(2)
        answers.map { it.code() }.assert().contains(FrameworkCodes.COMMAND_UNKNOWN)
        silent.bypassed("mail").assert().containsExactly(keyOf(held))
        events.filterIsInstance<ObservedEvent.PeerBypassed>().assert().hasSize(2)
    }

    @Test
    fun `with no list read again, a bypass should lapse after the time a list is kept`() {
        val held = peer(Reply.HELD)
        val live = peer(Reply(200))
        listed = listOf(held.base, live.base)
        generation = null
        val transport = transport()
        transport.send()
        now.addAndGet(LAPSE.inWholeNanoseconds - 1)
        transport.send()
        silent.bypassed("mail").assert().containsExactly(keyOf(held))
        now.addAndGet(1)
        transport.send()
        silent.bypassed("mail").assert().isEmpty()
        events.assert().contains(
            ObservedEvent.PeerRestored("mail", keyOf(held), LAPSE.inWholeMilliseconds, Reason.LAPSED)
        )
    }

    @Test
    fun `with every node bypassed, the requests should still be sent, and a connection made should not bring one back`() {
        val held = peer(Reply.HELD)
        listed = listOf(held.base)
        val transport = transport()
        transport.send().code().assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        // Its only node is bypassed: the request goes to it all the same, which takes the connection and the request.
        transport.send().code().assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        held.taken.assert().hasSize(2)
        silent.bypassed("mail").assert().containsExactly(keyOf(held))
        // Logged and observed once, however many requests it left unanswered.
        events.assert().containsExactly(ObservedEvent.PeerBypassed("mail", keyOf(held)))
    }

    @Test
    fun `a node that answers a request, whatever it answers, should be back`() {
        val slow = peer(Reply.HELD, Reply(503))
        listed = listOf(slow.base)
        val transport = transport()
        transport.send()
        now.addAndGet(2.seconds.inWholeNanoseconds)
        transport.send().assert().isInstanceOf(Answer.Problem::class.java)
        silent.bypassed("mail").assert().isEmpty()
        events.last().assert().isEqualTo(ObservedEvent.PeerRestored("mail", keyOf(slow), 2_000, Reason.ANSWERED))
    }

    @Test
    fun `a query's unanswered request should bypass the node for the commands too, the node's clients sharing one`() {
        val held = peer(Reply.HELD)
        val live = peer(Reply(200))
        listed = listOf(held.base, live.base)
        val queries = transport()
        queries.send(Exchange.QUERY).code().assert().isEqualTo(FrameworkCodes.QUERY_TIMEOUT)
        silent.bypassed("mail").assert().containsExactly(keyOf(held))
        // Another transport of the node, its commands', shares the bypass.
        val commands = transport()
        repeat(3) { commands.send().assert().isInstanceOf(Answer.Ok::class.java) }
        held.taken.assert().hasSize(1)
    }

    @Test
    fun `a node that refuses the connection should be failed over as before, not bypassed`() {
        withRefusingPort { port ->
            val live = peer(Reply(200))
            listed = listOf(URI.create("http://127.0.0.1:$port"), live.base)
            val transport = transport()
            repeat(2) { transport.send().assert().isInstanceOf(Answer.Ok::class.java) }
            silent.bypassed("mail").assert().isEmpty()
            events.assert().isEmpty()
        }
    }

    private companion object {
        val BOUND = 300.milliseconds
        val LAPSE = 30.seconds
    }
}
