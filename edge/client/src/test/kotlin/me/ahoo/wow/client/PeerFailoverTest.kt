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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A peer's nodes, in turn (decision D305): the requests rotate through the targets the resolver gives, and fail over
 * to the next on a connection that was not made or on a node that refused before handling, each target once, without
 * a retry; a bare 503 is the peer's answer.
 */
class PeerFailoverTest {
    private val unreachable = CopyOnWriteArrayList<String>()

    private fun transport(vararg targets: URI) = transport(
        object : EndpointResolver {
            override suspend fun resolve(context: String, port: Port): URI? = targets.firstOrNull()

            override suspend fun resolveAll(context: String, port: Port): List<URI> = targets.toList()

            override fun unreachable(context: String, port: Port) {
                unreachable += context
            }
        },
        RetryPolicy.NONE,
    )

    private fun transport(endpoints: EndpointResolver, retry: RetryPolicy) = HttpTransport(
        endpoints,
        ClientMode.Service("basket-service"),
        HttpCommandCaller.client(),
        5.seconds,
        retry,
    )

    private fun HttpTransport.get() = runBlocking {
        exchange(PeerRequest("mail", "/mail/box/x", Caller.ANONYMOUS, null), Exchange.CALL)
    }

    @Test
    fun `the requests should rotate through the targets`() {
        FakePeer().reply(Reply(200)).use { a ->
            FakePeer().reply(Reply(200)).use { b ->
                val transport = transport(a.base, b.base)
                repeat(4) { transport.get().assert().isInstanceOf(Answer.Ok::class.java) }
                a.taken.assert().hasSize(2)
                b.taken.assert().hasSize(2)
            }
        }
    }

    @Test
    fun `a target that refuses the connection should be passed over in the same attempt`() {
        withRefusingPort { port ->
            FakePeer().reply(Reply(200)).use { live ->
                val transport = transport(URI.create("http://127.0.0.1:$port"), live.base)
                repeat(2) { transport.get().assert().isInstanceOf(Answer.Ok::class.java) }
                live.taken.assert().hasSize(2)
                unreachable.assert().isEmpty()
            }
            // No node reached: wow.peer.unavailable, its connection's failure the cause.
            val down = transport(URI.create("http://127.0.0.1:$port")).get() as Answer.Problem
            down.problem.code.assert().isEqualTo(FrameworkCodes.PEER_UNAVAILABLE)
            down.problem.cause.assert().isInstanceOf(IOException::class.java)
            unreachable.assert().containsExactly("mail")
        }
        assertThrows<IllegalStateException> { transport().get() }.message.assert().contains("wow.peers.mail.url")
    }

    @Test
    fun `a peer the resolver reaches with no node up for the moment should be unavailable and looked up again`() {
        FakePeer().reply(Reply(200)).use { live ->
            // The registry lists no instance at first, then the one that came back.
            val lookups = ArrayDeque(listOf(emptyList(), listOf(live.base)))
            val resolver = object : EndpointResolver {
                override suspend fun resolve(context: String, port: Port): URI? = null

                override suspend fun resolveAll(context: String, port: Port): List<URI> =
                    lookups.removeFirstOrNull() ?: listOf(live.base)

                override suspend fun reaches(context: String, port: Port): Boolean = true

                override fun unreachable(context: String, port: Port) {
                    unreachable += context
                }
            }
            val once = transport(resolver, RetryPolicy.NONE)
            val down = once.get() as Answer.Problem
            down.problem.code.assert().isEqualTo(FrameworkCodes.PEER_UNAVAILABLE)
            down.problem.detail.assert().isEqualTo("The lookup of mail lists no node on its internal port now.")
            unreachable.assert().containsExactly("mail")
            live.taken.assert().isEmpty()

            // Not sent, so retried: the next attempt asks the resolver again and finds the node.
            lookups.addFirst(emptyList())
            transport(resolver, RetryPolicy.of(2, backoff = 10.milliseconds)).get()
                .assert().isInstanceOf(Answer.Ok::class.java)
            live.taken.assert().hasSize(1)
        }
    }

    @Test
    fun `a node's own refusal before handling should fail over, a bare 503 should not`() {
        val standby = Reply.problem(503, mapOf("code" to FrameworkCodes.NODE_STANDBY, "status" to 503))
        FakePeer().reply(standby).use { a ->
            FakePeer().reply(Reply(200)).use { b ->
                transport(a.base, b.base).get().assert().isInstanceOf(Answer.Ok::class.java)
                a.taken.assert().hasSize(1)
                b.taken.assert().hasSize(1)
            }
        }
        FakePeer().reply(standby).use { a ->
            // Every node refused: the last refusal is the answer.
            (transport(a.base).get() as Answer.Problem).problem.code.assert().isEqualTo(FrameworkCodes.NODE_STANDBY)
        }
        FakePeer().reply(Reply.problem(503, mapOf("status" to 503))).use { a ->
            FakePeer().reply(Reply(200)).use { b ->
                (transport(a.base, b.base).get() as Answer.Problem).problem.status.assert().isEqualTo(503)
                b.taken.assert().isEmpty()
            }
        }
    }

    @Test
    fun `a resolver's defaults should be its one URL`(): Unit = runBlocking {
        val one = EndpointResolver.of(emptyMap(), mapOf("mail" to URI.create("http://mail:9090")))
        one.resolveAll("mail", Port.INTERNAL).assert().containsExactly(URI.create("http://mail:9090"))
        one.reaches("mail", Port.INTERNAL).assert().isTrue()
        one.reaches("box", Port.INTERNAL).assert().isFalse()
        one.unreachable("mail", Port.INTERNAL)
    }
}
