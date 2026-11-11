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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.spi.call.OutgoingCall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A request sent and not answered, its connection lost in the answer's body: the problem its kind gives it, retried
 * as an answered problem is, as the retry policy says.
 */
class UnansweredRequestsTest {
    private val peer = FakePeer()

    @AfterEach
    fun close() {
        peer.close()
    }

    private val ref = BOX.ref("t1", "box-1")

    /** An answer cut in its body: its status and headers sent, one byte of the body, then the connection closed. */
    private val cut = Reply(200, mapOf("Content-Type" to "application/json"), chunks = listOf("{"), abort = true)

    private fun client(attempts: Int) = WowClient.builder()
        .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
        .service("reader-service")
        .contracts(contractOf(BOX, HttpCommandClientTest.DeliverItems::class.java))
        .retry(RetryPolicy.of(maxAttempts = attempts, backoff = 1.milliseconds))
        .build()

    private fun deliver() = CommandRequest.to(ref, HttpCommandClientTest.DeliverItems(emptyList()))

    @Test
    fun `a command whose answer was cut should be sent again with its request id`(): Unit = runBlocking {
        peer.reply(cut, Reply.json(resultOf(ref)))
        client(attempts = 2).commands.wait(deliver()).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        peer.taken.assert().hasSize(2)
        peer.taken.map { it.headers["Wow-Request-Id"] }.distinct().assert().hasSize(1)
    }

    @Test
    fun `a command cut on every attempt should fail as unknown once the policy's attempts are spent`() {
        peer.reply(cut)
        caught<WowProblemException> { runBlocking { client(attempts = 2).commands.wait(deliver()) } }
            .code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        peer.taken.assert().hasSize(2)
    }

    @Test
    fun `a query whose answer was cut should be asked again`(): Unit = runBlocking {
        val record = mapOf(
            "tenantId" to "t1",
            "aggregateId" to "box-1",
            "version" to 1,
            "deleted" to false,
            "firstEventTime" to 1,
            "lastEventTime" to 2,
            "state" to mapOf("items" to 1),
        )
        peer.reply(cut, Reply.json(listOf(record)))
        client(attempts = 2).queries.of(Caller.of(tenantId = "t1")).snapshots(BOX).list(ListQuery(MatchAllFilter))
            .toList().map { it.ref.id }.assert().containsExactly("box-1")
        peer.taken.assert().hasSize(2)
    }

    @Test
    fun `a saga's call whose answer was cut should not be sent again here, its subscription retries it`() {
        peer.reply(cut, Reply.json(resultOf(ref)))
        val caller = HttpCommandCaller(
            EndpointResolver.of(emptyMap(), mapOf("mail" to peer.base)),
            "basket-service",
            HttpCommandCaller.client(),
            5.seconds,
            contracts = contractOf(BOX, HttpCommandClientTest.DeliverItems::class.java).map { it.manifest },
        )
        val command = Command(
            "c-1",
            "r-1",
            ref,
            HttpCommandClientTest.DeliverItems(emptyList()),
            causation = Causation("root", "root"),
        )
        caught<WowProblemException> { runBlocking { caller.call(OutgoingCall("mail.box.DeliverItems", command)) } }
            .code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        peer.taken.assert().hasSize(1)
    }
}
