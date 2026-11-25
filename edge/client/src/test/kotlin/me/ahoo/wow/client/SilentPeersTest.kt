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
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.problem.WowProblemException
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

    private fun transport(retry: RetryPolicy = RetryPolicy.NONE) = HttpTransport(
        endpoints,
        ClientMode.Service("basket-service"),
        HttpCommandCaller.client(),
        BOUND,
        retry,
        silent = silent,
    )

    private fun HttpTransport.send(kind: Exchange = Exchange.COMMAND): Answer<ByteArray> = runBlocking {
        exchange(PeerRequest("mail", "/mail/box/x", Caller.ANONYMOUS, null), kind)
    }

    private fun Answer<*>.code(): String? = (this as? Answer.Problem)?.problem?.code

    /** [answer] as a failure says it: a problem's code, detail and cause, and the nodes bypassed since. */
    private fun described(answer: Answer<*>): String {
        val problem = (answer as? Answer.Problem)?.problem ?: return "$answer"
        return "${problem.code} (${problem.status}): ${problem.detail}; cause ${problem.cause}; " +
            "bypassed ${silent.bypassed("mail", Port.INTERNAL)}"
    }

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
        silent.bypassed("mail", Port.INTERNAL).assert().containsExactly(keyOf(held))
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
        silent.bypassed("mail", Port.INTERNAL).assert().isEmpty()
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
        silent.bypassed("mail", Port.INTERNAL).assert().containsExactly(keyOf(held))
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
        silent.bypassed("mail", Port.INTERNAL).assert().containsExactly(keyOf(held))
        now.addAndGet(1)
        transport.send()
        silent.bypassed("mail", Port.INTERNAL).assert().isEmpty()
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
        silent.bypassed("mail", Port.INTERNAL).assert().containsExactly(keyOf(held))
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
        silent.bypassed("mail", Port.INTERNAL).assert().isEmpty()
        events.last().assert().isEqualTo(ObservedEvent.PeerRestored("mail", keyOf(slow), 2_000, Reason.ANSWERED))
    }

    @Test
    fun `a query's unanswered request should bypass the node for the commands too, the node's clients sharing one`() {
        val held = peer(Reply.HELD)
        val live = peer(Reply(200))
        listed = listOf(held.base, live.base)
        val queries = transport()
        queries.send(Exchange.QUERY).code().assert().isEqualTo(FrameworkCodes.QUERY_TIMEOUT)
        silent.bypassed("mail", Port.INTERNAL).assert().containsExactly(keyOf(held))
        // Another transport of the node, its commands', shares the bypass.
        val commands = transport()
        repeat(3) { commands.send().assert().isInstanceOf(Answer.Ok::class.java) }
        held.taken.assert().hasSize(1)
    }

    @Test
    fun `a port's list should keep its own bypasses, the other port's choices and lookups ending none of them`() {
        val held = peer(Reply.HELD)
        val live = peer(Reply(200))
        val public = peer(Reply(200))
        listed = listOf(held.base, live.base)
        // The context's public port: its fixed URL, a list of its own, of another generation.
        val ports = object : EndpointResolver {
            override suspend fun resolve(context: String, port: Port): URI? = resolveAll(context, port).firstOrNull()

            override suspend fun resolveAll(context: String, port: Port): List<URI> = when (port) {
                Port.INTERNAL -> PeerList(listed, 1)
                else -> PeerList(listOf(public.base), 7)
            }
        }
        // The node's saga caller or service-mode client, and its user-mode client, sharing one set of bypasses.
        val internal = HttpTransport(
            ports,
            ClientMode.Service("basket-service"),
            HttpCommandCaller.client(),
            BOUND,
            RetryPolicy.NONE,
            silent = silent,
        )
        val user = HttpTransport(
            ports,
            ClientMode.User(UserCredential.FORWARDED),
            HttpCommandCaller.client(),
            BOUND,
            RetryPolicy.NONE,
            silent = silent,
        )
        internal.send().code().assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        repeat(3) { user.send().assert().isInstanceOf(Answer.Ok::class.java) }
        // The public list neither lists the internal node nor shares its generation: the bypass holds all the same.
        silent.bypassed("mail", Port.INTERNAL).assert().containsExactly(keyOf(held))
        silent.bypassed("mail", Port.PUBLIC).assert().isEmpty()
        events.assert().containsExactly(ObservedEvent.PeerBypassed("mail", keyOf(held)))
        repeat(3) { internal.send().assert().isInstanceOf(Answer.Ok::class.java) }
        held.taken.assert().hasSize(1)
        // Each list settles its own: the internal list read again brings its node back.
        listed = listOf(held.base, live.base)
        val relisted = object : EndpointResolver by ports {
            override suspend fun resolveAll(context: String, port: Port): List<URI> =
                if (port == Port.INTERNAL) PeerList(listed, 2) else ports.resolveAll(context, port)
        }
        HttpTransport(
            relisted,
            ClientMode.Service("basket-service"),
            HttpCommandCaller.client(),
            BOUND,
            RetryPolicy.NONE,
            silent = silent,
        ).send()
        events.filterIsInstance<ObservedEvent.PeerRestored>().single().reason.assert().isEqualTo(Reason.RELISTED)
    }

    /**
     * The node is [withRefusingPort]'s port, bound without listening, which names both paths of a connection not made:
     * on Linux it is refused at once; on macOS it is never answered, a connect still pending when the bound passes,
     * which the JDK's own request timeout says was not made, whichever timer fires first (#6993, `ConnectNotMadeTest`).
     * Either way the request was not sent: it fails over, and the node is not passed over as silent.
     */
    @Test
    fun `a node that refuses the connection should be failed over as before, not bypassed`() {
        withRefusingPort { port ->
            val live = peer(Reply(200))
            val refusing = URI.create("http://127.0.0.1:$port")
            listed = listOf(refusing, live.base)
            val transport = transport()
            repeat(2) { round ->
                val answer = transport.send()
                val nodes = "refusing $refusing, live ${live.base}"
                answer.assert().describedAs { "send $round, $nodes: ${described(answer)}" }
                    .isInstanceOf(Answer.Ok::class.java)
            }
            silent.bypassed("mail", Port.INTERNAL).assert().isEmpty()
            events.assert().isEmpty()
        }
    }

    @Test
    fun `a command a node takes and never answers should not be sent again to it`() {
        val held = peer(Reply.HELD)
        listed = listOf(held.base)
        val client = WowClient.builder()
            .endpoints(endpoints)
            .service("reader-service")
            .contracts(contractOf(BOX, HttpCommandClientTest.DeliverItems::class.java))
            .retry(RetryPolicy.of(maxAttempts = 3, backoff = 1.milliseconds))
            .build()
        val deliver = CommandRequest.builder(BOX.ref("t1", "box-1"), HttpCommandClientTest.DeliverItems(emptyList()))
            .timeout(300.milliseconds)
            .build()
        val started = TimeSource.Monotonic.markNow()
        val failure = caught<WowProblemException> { runBlocking { client.commands.wait(deliver) } }
        failure.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        // Never sent again to the node that went silent, and no other node is live: one request, ended at its wait and
        // the grace, though the policy allows three attempts (#6809).
        held.taken.assert().hasSize(1)
        started.elapsedNow().assert().isLessThan(10.seconds)
    }

    @Test
    fun `a request a node went silent on should be sent again once, to another live node, with its request id`() {
        val held = peer(Reply.HELD)
        val live = peer(Reply(200))
        listed = listOf(held.base, live.base)
        val answer = runBlocking {
            transport(RETRIES).exchange(
                PeerRequest("mail", "/mail/box/x", Caller.ANONYMOUS, null, mapOf("Wow-Request-Id" to "r-1")),
                Exchange.COMMAND,
            )
        }
        // The live node forwards it to the partition's owner, which settles it as a duplicate or a commit.
        answer.assert().isInstanceOf(Answer.Ok::class.java)
        held.taken.assert().hasSize(1)
        live.taken.map { it.headers["Wow-Request-Id"] }.assert().containsExactly("r-1")
    }

    @Test
    fun `a request two nodes went silent on should fail once as unknown, never sent to either again`() {
        val first = peer(Reply.HELD)
        val second = peer(Reply.HELD)
        listed = listOf(first.base, second.base)
        transport(RETRIES).send().code().assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        // At most one resend: the second node's silence ends the request, though the policy allows a third attempt.
        first.taken.assert().hasSize(1)
        second.taken.assert().hasSize(1)
    }

    @Test
    fun `the resend of a request a node went silent on should end with what it meets, its outcome still unknown`() {
        // The other node refuses the connection: nothing more was sent, and the outcome stays the silent node's.
        withRefusingPort { port ->
            val held = peer(Reply.HELD)
            listed = listOf(held.base, URI.create("http://127.0.0.1:$port"))
            transport(RETRIES).send().code().assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
            held.taken.assert().hasSize(1)
        }
        // The other node answers a problem a retry may get past: not retried, the resend being the request's last.
        val held = peer(Reply.HELD)
        val busy = peer(Reply.problem(503, mapOf("code" to FrameworkCodes.STORAGE_UNAVAILABLE), retryAfter = "0"))
        listed = listOf(held.base, busy.base)
        transport(RETRIES).send().code().assert().isEqualTo(FrameworkCodes.STORAGE_UNAVAILABLE)
        busy.taken.assert().hasSize(1)
        // The other node refuses it before handling, stopping: that says only the resend was not taken, and nothing
        // of what the silent node did, so the outcome stays unknown, never "not taken" (#6806).
        val silentAgain = peer(Reply.HELD)
        val stopping = peer(Reply.problem(503, mapOf("code" to FrameworkCodes.NODE_CLOSING), retryAfter = "0"))
        listed = listOf(silentAgain.base, stopping.base)
        transport(RETRIES).send().code().assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        stopping.taken.assert().hasSize(1)
    }

    private companion object {
        val BOUND = 300.milliseconds
        val LAPSE = 30.seconds

        /** A policy that would send a request three times. */
        val RETRIES = RetryPolicy.of(maxAttempts = 3, backoff = 1.milliseconds)
    }
}
