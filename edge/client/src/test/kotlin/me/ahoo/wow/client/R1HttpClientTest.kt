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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Review r1 (the HTTP edge, OpenAPI and the client): what `HttpCommandClient` promises about a command whose outcome is
 * unknown, its bound, and what it tells its caller to do next.
 */
class R1HttpClientTest {
    private val peer = FakePeer()

    @AfterEach
    fun close() {
        peer.close()
    }

    private val ref = BOX.ref("t1", "box-1")
    private val contracts = contractOf(BOX, HttpCommandClientTest.DeliverItems::class.java)

    /** An answer cut in its body: its status and headers sent, one byte of the body, then the connection closed. */
    private val cut = Reply(200, mapOf("Content-Type" to "application/json"), chunks = listOf("{"), abort = true)

    private fun client(
        endpoints: EndpointResolver = EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)),
        attempts: Int = 1,
        user: Boolean = false,
    ) = WowClient.builder()
        .endpoints(endpoints)
        .apply { if (user) user(UserCredential.NONE) else service("reader-service") }
        .contracts(contracts)
        .retry(RetryPolicy.of(maxAttempts = attempts, backoff = 1.milliseconds))
        .build()

    private fun deliver(timeout: kotlin.time.Duration? = null) =
        CommandRequest.builder(ref, HttpCommandClientTest.DeliverItems(emptyList()))
            .apply { timeout?.let { timeout(it) } }
            .build()

    @Test
    fun `a command whose outcome is unknown stays unknown when its retry finds no node to connect to`() {
        withRefusingPort { refusing ->
            // The first lookup lists the node that took the command and cut its answer; the next, a node gone since.
            val lookups = AtomicInteger()
            val gone = URI.create("http://127.0.0.1:$refusing")
            val resolver = object : EndpointResolver {
                override suspend fun resolve(context: String, port: Port): URI? = null

                override suspend fun resolveAll(context: String, port: Port): List<URI> =
                    listOf(if (lookups.getAndIncrement() == 0) peer.base else gone)

                override suspend fun reaches(context: String, port: Port): Boolean = true
            }
            peer.reply(cut)
            val failure = caught<WowProblemException> {
                runBlocking { client(resolver, attempts = 2).commands.wait(deliver()) }
            }
            peer.taken.assert().hasSize(1)
            // The command was taken: whether it committed is unknown, and a retry keeps its request id.
            failure.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN) // fails: wow.peer.unavailable
            failure.retry.assert().isEqualTo(Retry.SAME_REQUEST) // which says: new request, nothing was changed
        }
    }

    @Test
    fun `a command whose answer stops after its head ends at its wait and the grace`() {
        // The head and one byte of the body, then nothing: a node gone mid-answer without closing the connection.
        peer.reply(
            Reply(200, mapOf("Content-Type" to "application/json"), chunks = listOf("{"), holdAfterChunks = true),
        )
        val started = TimeSource.Monotonic.markNow()
        val failure = runBlocking {
            withTimeoutOrNull(20.seconds) {
                runCatching { client().commands.wait(deliver(timeout = 300.milliseconds)) }.exceptionOrNull()
            }
        }
        val took = started.elapsedNow()
        failure.assert().describedAs("the command's answer after $took").isNotNull() // fails: no bound
        (failure as WowProblemException).code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        started.elapsedNow().assert().isLessThan(10.seconds)
    }

    @Test
    fun `a user's command stream cut after its head fails as an unknown outcome`() {
        val accepted = WowJson.mapper.writeValueAsString(resultOf(ref, hop = Hop.accepted("mail")))
        peer.reply(Reply.cut("id: 0\nevent: accepted\ndata: $accepted\n\n"))
        val failure = runCatching {
            runBlocking { client(user = true).commands.stream(deliver()).toList() }
        }.exceptionOrNull()
        // One kind of failure, whatever met it: the command was taken and its outcome is unknown.
        failure.assert().isInstanceOf(WowProblemException::class.java) // fails: a raw IOException
        (failure as WowProblemException).code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
    }

    @Test
    fun `a command a node takes and never answers is not sent again`() {
        peer.reply(Reply.HELD)
        val started = TimeSource.Monotonic.markNow()
        val failure = caught<WowProblemException> {
            runBlocking { client(attempts = 3).commands.wait(deliver(timeout = 300.milliseconds)) }
        }
        failure.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        // "It is not sent again, since whether it ran is unknown": one request, ended at its wait and the grace.
        peer.taken.assert().hasSize(1) // fails: 3, each held for the wait and the grace
        started.elapsedNow().assert().isLessThan(10.seconds)
    }

    @Test
    fun `a gateway's bare 504 or 502 is no decided answer`() {
        listOf(504, 502).forEach { status ->
            peer.reply(Reply(status, mapOf("Content-Type" to "text/html"), "<html>Bad Gateway</html>".toByteArray()))
            val failure = caught<WowProblemException> { runBlocking { client().commands.wait(deliver()) } }
            // The gateway took the command and does not say whether the node committed it: "NONE fails alike again".
            failure.retry.assert().describedAs("the retry of a bare $status").isNotEqualTo(Retry.NONE) // fails: NONE
        }
    }
}
