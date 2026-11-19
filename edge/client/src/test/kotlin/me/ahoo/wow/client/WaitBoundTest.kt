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
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.manifest.CommandIndex
import me.ahoo.wow.spi.call.OutgoingCall
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A command or a call to a peer waits for its answer no longer than the peer can take to give it: its wait, which it
 * states as `Wow-Wait-Timeout`, and the answer's grace. The transport's timeout (`wow.peer.timeout`, 30 seconds by
 * default) bounds what has no wait. A peer gone without closing its connections (a pod deleted by force) costs a
 * request that much, not the transport's timeout (#6517).
 *
 * The peer here takes each request and never answers it ([Reply.HELD]): nothing ends a request but the client's bound,
 * so the time it took is that bound, at least, and far less than the transport's 30 seconds.
 */
class WaitBoundTest {
    private val peer = FakePeer()

    @AfterEach
    fun close() {
        peer.close()
    }

    private val ref = BOX.ref("t1", "box-1")

    private val contracts = contractOf(BOX, HttpCommandClientTest.DeliverItems::class.java)

    /** A saga's caller of the peer, with the transport's [timeout] (`wow.peer.timeout`), reading time at [NOW]. */
    private fun caller(timeout: Duration = PEER_TIMEOUT, timeouts: Map<String, Duration> = emptyMap()) =
        HttpCommandCaller(
            HttpTransport(
                EndpointResolver.of(emptyMap(), mapOf("mail" to peer.base)),
                ClientMode.Service("basket-service"),
                HttpCommandCaller.client(),
                timeout,
                RetryPolicy.NONE,
                timeouts,
            ),
            CommandIndex(contracts.map { it.manifest }),
            clock = { NOW },
        )

    /** A saga's call of the box, going on with a wait that ends [left] after [NOW], or with none. */
    private fun call(left: Duration? = null): OutgoingCall {
        val command = Command(
            "c-1",
            "r-1",
            ref,
            HttpCommandClientTest.DeliverItems(emptyList()),
            causation = Causation("root", "root"),
        )
        val origin = Origin("node-1", "127.0.0.1:1", "basket-service")
        val wait = left?.let { WaitContext("w-1", origin, emptyList(), NOW + it.inWholeMilliseconds) }
        return OutgoingCall("mail.box.DeliverItems", command, wait)
    }

    private inline fun timed(block: () -> Unit): Duration {
        val started = TimeSource.Monotonic.markNow()
        block()
        return started.elapsedNow()
    }

    @Test
    fun `a saga's call to a peer that never answers ends at its wait and the grace, not the transport's timeout`() {
        peer.reply(Reply.HELD)
        val elapsed = timed {
            caught<WowProblemException> { runBlocking { caller().call(call(left = 300.milliseconds)) } }
                .code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        }
        // The client's bound, the wait's 300 ms and the grace, ended it: nothing else could.
        elapsed.assert().isGreaterThanOrEqualTo(300.milliseconds + HttpCommandCaller.ANSWER_GRACE)
        elapsed.assert().isLessThan(PEER_TIMEOUT / 3)
        // It told the peer: the peer's wait ends where the caller's does.
        peer.taken.single().headers[WowHeaders.WAIT_TIMEOUT].assert().isEqualTo("300ms")
    }

    @Test
    fun `a call with no wait of its own is bounded by the default wait, at most its context's timeout`() {
        // The wait the target applies by default, stated, and the grace: not the transport's 30 seconds.
        val defaulted = caller().requestOf(call())
        defaulted.headers[WowHeaders.WAIT_TIMEOUT].assert().isEqualTo("10s")
        defaulted.timeout.assert().isEqualTo(10.seconds + HttpCommandCaller.ANSWER_GRACE)
        // A context given a shorter timeout keeps it, its wait too.
        val short = caller(timeouts = mapOf("mail" to 500.milliseconds)).requestOf(call())
        short.headers[WowHeaders.WAIT_TIMEOUT].assert().isEqualTo("500ms")
        short.timeout.assert().isEqualTo(500.milliseconds + HttpCommandCaller.ANSWER_GRACE)
        // A wait that ran out still asks the target to try for a millisecond: it answers 504 at once.
        caller().requestOf(call(left = (-5).seconds)).headers[WowHeaders.WAIT_TIMEOUT].assert().isEqualTo("1ms")
    }

    @Test
    fun `a call answered with a FAILED result keeps its Retry-After for the subscription's backoff`() {
        val timedOut = resultOf(
            ref,
            CommandResult.Status.FAILED,
            ErrorInfo(FrameworkCodes.WAIT_TIMEOUT, "The wait ran out."),
            hop = Hop.accepted("mail"),
        )
        peer.reply(
            Reply.problem(
                504,
                mapOf("code" to FrameworkCodes.WAIT_TIMEOUT, "retry" to "same-request", "result" to timedOut),
                retryAfter = "1",
            ),
        )
        val failure = caught<WowProblemException> { runBlocking { caller().call(call()) } }
        failure.code.assert().isEqualTo(FrameworkCodes.WAIT_TIMEOUT)
        failure.result!!.status.assert().isEqualTo(CommandResult.Status.FAILED)
        // The backoff waits at least the second the target asked for, not the backpressure's first tenth.
        FailureClassification.retryAfterOf(IllegalStateException("handler", failure)).assert().isEqualTo(1.seconds)
    }

    @Test
    fun `a client's command to a peer that never answers ends at its wait and the grace`() {
        peer.reply(Reply.HELD)
        val client = WowClient.builder()
            .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
            .service("reader-service")
            .contracts(contracts)
            .retry(RetryPolicy.of(maxAttempts = 1, backoff = 1.milliseconds))
            .build()
        val request = CommandRequest.builder(ref, HttpCommandClientTest.DeliverItems(emptyList()))
            .timeout(300.milliseconds)
            .build()
        val elapsed = timed {
            caught<WowProblemException> { runBlocking { client.commands.wait(request) } }
                .code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        }
        elapsed.assert().isGreaterThanOrEqualTo(300.milliseconds)
        elapsed.assert().isLessThan(PEER_TIMEOUT / 3)
        peer.taken.single().headers[WowHeaders.WAIT_TIMEOUT].assert().isEqualTo("300ms")
    }

    private companion object {
        /** The transport's timeout, `wow.peer.timeout`'s default. */
        val PEER_TIMEOUT = 30.seconds

        const val NOW = 1_000_000L
    }
}
