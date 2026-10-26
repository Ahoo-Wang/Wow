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
import me.ahoo.wow.manifest.FrameworkErrors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * A peer's nodes, in turn (decision D305): the requests rotate through the targets the resolver gives, and fail over
 * to the next on a connection that was not made or on a node that refused before handling, each target once, without
 * a retry; a bare 503 is the peer's answer.
 */
class PeerFailoverTest {
    private val unreachable = CopyOnWriteArrayList<String>()

    private fun transport(vararg targets: URI) = HttpTransport(
        object : EndpointResolver {
            override suspend fun resolve(context: String, port: Port): URI? = targets.firstOrNull()

            override suspend fun resolveAll(context: String, port: Port): List<URI> = targets.toList()

            override fun unreachable(context: String, port: Port) {
                unreachable += context
            }
        },
        ClientMode.Service("basket-service"),
        HttpCommandCaller.client(),
        5.seconds,
        RetryPolicy.NONE,
    )

    private fun HttpTransport.get() = runBlocking {
        exchange(PeerRequest("mail", "/mail/box/x", Caller.ANONYMOUS, null)) { false }
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
            assertThrows<IOException> { transport(URI.create("http://127.0.0.1:$port")).get() }
            unreachable.assert().containsExactly("mail")
        }
        assertThrows<IllegalStateException> { transport().get() }.message.assert().contains("wow.peers.mail.url")
    }

    @Test
    fun `a node's own refusal before handling should fail over, a bare 503 should not`() {
        val standby = Reply.problem(503, mapOf("code" to FrameworkErrors.NODE_STANDBY, "status" to 503))
        FakePeer().reply(standby).use { a ->
            FakePeer().reply(Reply(200)).use { b ->
                transport(a.base, b.base).get().assert().isInstanceOf(Answer.Ok::class.java)
                a.taken.assert().hasSize(1)
                b.taken.assert().hasSize(1)
            }
        }
        FakePeer().reply(standby).use { a ->
            // Every node refused: the last refusal is the answer.
            (transport(a.base).get() as Answer.Problem).problem.code.assert().isEqualTo(FrameworkErrors.NODE_STANDBY)
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
