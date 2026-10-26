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
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.SendOptions
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.wire.CallWires
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The HTTP command client (decision D257) against a fake peer: its two modes, its problems and its retries. */
class HttpCommandClientTest {
    data class DeliverItems(val items: List<String>)

    private val peer = FakePeer()

    @AfterEach
    fun close() {
        peer.close()
    }

    private val ref = BOX.ref("t1", "a b")
    private val alice = Caller.of(
        tenantId = "t1",
        scope = Scope("alice"),
        subject = "alice",
        attributes = mapOf(Caller.APP to listOf("console")),
    )

    private fun client(
        mode: ClientMode = ClientMode.Service(SERVICE),
        retry: RetryPolicy = RetryPolicy.of(backoff = 1.milliseconds),
        endpoints: EndpointResolver = EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)),
    ) = WowClient.builder()
        .endpoints(endpoints)
        .apply {
            when (mode) {
                is ClientMode.Service -> service(checkNotNull(mode.service))
                is ClientMode.User -> user(mode.credential)
            }
        }
        .retry(retry)
        .contracts(contractOf(BOX, DeliverItems::class.java))
        .build()
        .commands

    private fun committed(status: CommandResult.Status = CommandResult.Status.SUCCEEDED, error: ErrorInfo? = null) =
        resultOf(ref, status, error)

    @Test
    fun `a service-mode command should go to the internal port as the service, asserting the caller as its root`(): Unit =
        runBlocking {
            peer.reply(Reply.json(committed()))
            val result = client().of(
                alice
            ).wait(DeliverItems(listOf("mug")), ref, SendOptions.of(wait = WaitPlan.DEFAULT))
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            val request = peer.taken.single()
            request.method.assert().isEqualTo("POST")
            request.path.assert().isEqualTo("/mail/box/a%20b/deliver-items")
            request.headers[WowHeaders.SERVICE].assert().isEqualTo(SERVICE)
            request.headers[WowHeaders.REQUEST_ID].assert().isNotBlank()
            request.headers.containsKey(WowHeaders.WAIT).assert().isFalse()
            WowJson.mapper.readTree(request.body)["items"][0].asString().assert().isEqualTo("mug")
            val root = CallWires.decode(request.headers.getValue(WowHeaders.CALL))
            root.tenantId.assert().isEqualTo("t1")
            Scope(root.ownerId, root.spaceId).assert().isEqualTo(Scope("alice"))
            root.operator.assert().isEqualTo(Operator("alice", "t1", false))
            root.facts.assert().isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
            root.causation.correlationId.assert().isEqualTo(root.commandId)
        }

    @Test
    fun `a service-mode command to another scope should assert that scope, acting elsewhere`(): Unit = runBlocking {
        peer.reply(Reply.json(committed()))
        val options = SendOptions.of(
            targetTenant = "t2",
            targetScope = Scope("bob"),
            requestId = "r-9",
            expectedVersion = 4
        )
        client().of(alice).send(DeliverItems(listOf("mug")), ref, options)
        val request = peer.taken.single()
        request.headers[WowHeaders.REQUEST_ID].assert().isEqualTo("r-9")
        request.headers[WowHeaders.IF_MATCH].assert().isEqualTo("\"4\"")
        request.headers.containsKey(WowHeaders.SCOPE).assert().isFalse()
        val root = CallWires.decode(request.headers.getValue(WowHeaders.CALL))
        root.tenantId.assert().isEqualTo("t2")
        Scope(root.ownerId, root.spaceId).assert().isEqualTo(Scope("bob"))
        root.operator!!.crossScope.assert().isTrue()
    }

    @Test
    fun `a user-mode command should go to the public routes with the forwarded credential and the wait`(): Unit =
        runBlocking {
            peer.reply(Reply.json(committed()))
            val options = SendOptions.of(
                wait = WaitPlan.of(Hop.committed(), Hop.snapshot()),
                timeout = 3.seconds,
                targetTenant = "t2",
                expectedVersion = 2,
            )
            withContext(ForwardedHeaders(mapOf("Authorization" to "Bearer user", "CoSec-App-Id" to "console"))) {
                client(
                    ClientMode.User(UserCredential.FORWARDED)
                ).of(alice).wait(DeliverItems(emptyList()), ref, options)
            }
            val request = peer.taken.single()
            request.headers["Authorization"].assert().isEqualTo("Bearer user")
            request.headers["CoSec-App-Id"].assert().isEqualTo("console")
            request.headers.containsKey(WowHeaders.CALL).assert().isFalse()
            request.headers[WowHeaders.WAIT].assert().isEqualTo("committed > snapshot")
            request.headers[WowHeaders.WAIT_TIMEOUT].assert().isEqualTo("3s")
            request.headers[WowHeaders.SCOPE].assert().isEqualTo("tenant=\"t2\", owner=\"alice\"")
            request.headers[WowHeaders.IF_MATCH].assert().isEqualTo("\"2\"")
        }

    @Test
    fun `a user-mode send should wait for the peer to accept the command`(): Unit = runBlocking {
        peer.reply(Reply.json(resultOf(ref, hop = Hop.accepted("mail")), status = 202))
        val result = client(ClientMode.User(UserCredential.NONE)).send(DeliverItems(emptyList()), BOX.unassigned("t1"))
        result.hop.stage.assert().isEqualTo(Stage.ACCEPTED)
        val request = peer.taken.single()
        request.path.assert().isEqualTo("/mail/box/deliver-items")
        request.headers[WowHeaders.WAIT].assert().isEqualTo("accepted")
        request.headers.containsKey(WowHeaders.WAIT_TIMEOUT).assert().isFalse()
        request.headers.containsKey("Authorization").assert().isFalse()
    }

    @Test
    fun `a rejection should be a CommandRejectedException, whether answered as a problem or a result`() {
        val rejected = committed(CommandResult.Status.REJECTED, ErrorInfo("mail.box.full", "Full."))
        peer.reply(Reply.problem(422, mapOf("code" to "mail.box.full", "result" to rejected)))
        caught<CommandRejectedException> {
            runBlocking { client().wait(DeliverItems(emptyList()), ref) }
        }.code.assert().isEqualTo("mail.box.full")
        peer.reply(Reply.json(rejected))
        assertThrownBy<CommandRejectedException> { runBlocking { client().wait(DeliverItems(emptyList()), ref) } }
        peer.taken.assert().hasSize(2)
    }

    @Test
    fun `a failure should be a WowProblemException with the hint of its code`() {
        val failed = committed(CommandResult.Status.FAILED, ErrorInfo("wow.command.failed", "Boom."))
        peer.reply(Reply.problem(500, mapOf("code" to "wow.command.failed", "detail" to "Boom.", "result" to failed)))
        val problem = caught<WowProblemException> { runBlocking { client().wait(DeliverItems(emptyList()), ref) } }
        problem.code.assert().isEqualTo("wow.command.failed")
        problem.status.assert().isEqualTo(500)
        problem.retryHint.retryable.assert().isFalse()
        problem.result.assert().isEqualTo(failed)
        problem.message.assert().contains("Boom.")
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a problem that is only HTTP's should be a WowProblemException of its status`() {
        peer.reply(Reply(401, mapOf("Content-Type" to "text/plain"), "who are you".toByteArray()))
        val problem = caught<WowProblemException> { runBlocking { client().wait(DeliverItems(emptyList()), ref) } }
        problem.code.assert().isEqualTo("http.401")
        problem.message.assert().contains("who are you")
    }

    @Test
    fun `a 429 should be sent again after its Retry-After, with the same request id`(): Unit = runBlocking {
        peer.reply(
            Reply.problem(429, mapOf("code" to "wow.command.busy"), retryAfter = "0"),
            Reply.json(committed()),
        )
        client().wait(DeliverItems(emptyList()), ref).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        peer.taken.assert().hasSize(2)
        peer.taken.map { it.headers[WowHeaders.REQUEST_ID] }.distinct().assert().hasSize(1)
        peer.taken.map { it.headers[WowHeaders.CALL] }.distinct().assert().hasSize(1)
    }

    @Test
    fun `a Retry-After longer than the policy honors should not be waited for`() {
        peer.reply(Reply.problem(429, mapOf("code" to "wow.command.busy"), retryAfter = "60"))
        val problem = caught<WowProblemException> { runBlocking { client().wait(DeliverItems(emptyList()), ref) } }
        problem.retryHint.retryAfter.assert().isEqualTo(60.seconds)
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a code that keeps the request id should be retried until the attempts run out`() {
        peer.reply(Reply.problem(503, mapOf("code" to "wow.command.unknown")))
        val problem = caught<WowProblemException> { runBlocking { client().wait(DeliverItems(emptyList()), ref) } }
        problem.retryHint.sameRequestId.assert().isTrue()
        peer.taken.assert().hasSize(3)
        peer.taken.map { it.headers[WowHeaders.REQUEST_ID] }.distinct().assert().hasSize(1)
    }

    @Test
    fun `a proxy's 503 without a code should be retried, a 504 never`() {
        peer.reply(Reply(503), Reply.json(committed()))
        runBlocking { client().wait(DeliverItems(emptyList()), ref) }
        peer.taken.assert().hasSize(2)
        peer.taken.clear()
        peer.reply(Reply.problem(504, mapOf("code" to "wow.wait.timeout")))
        val problem = caught<WowProblemException> { runBlocking { client().wait(DeliverItems(emptyList()), ref) } }
        problem.retryHint.sameRequestId.assert().isTrue()
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a peer that is down should be tried as often as the policy says, then its failure thrown`() {
        withRefusingPort { closed ->
            val down = EndpointResolver.of(emptyMap(), mapOf("mail" to URI.create("http://127.0.0.1:$closed")))
            val client = WowClient.builder().endpoints(down).connectTimeout(200.milliseconds)
                .retry(RetryPolicy.of(maxAttempts = 2, backoff = 1.milliseconds))
                .contracts(contractOf(BOX, DeliverItems::class.java)).build().commands
            // Refused, or never answered, as the platform has it: either way the connection was not made.
            caught<IOException> { runBlocking { client.wait(DeliverItems(emptyList()), ref) } }
        }
    }

    @Test
    fun `a context without a URL or a command without a contract should not be sent`() {
        caught<IllegalStateException> {
            runBlocking { client(endpoints = EndpointResolver.NONE).wait(DeliverItems(emptyList()), ref) }
        }.message.assert().contains("wow.peers.mail.url")
        caught<IllegalStateException> {
            runBlocking {
                client(ClientMode.User(UserCredential.NONE), endpoints = EndpointResolver.NONE)
                    .wait(DeliverItems(emptyList()), ref)
            }
        }.message.assert().contains("wow.peers.mail.public-url")
        val unnamed = WowClient.builder().contracts(emptyList()).build().commands
        assertThrownBy<IllegalArgumentException> { runBlocking { unnamed.wait(DeliverItems(emptyList()), ref) } }
        peer.taken.assert().isEmpty()
    }

    @Test
    fun `a 2xx answer that holds no result should fail`() {
        peer.reply(Reply(200, mapOf("Content-Type" to "application/json"), "not json".toByteArray()))
        assertThrownBy<IllegalStateException> { runBlocking { client().wait(DeliverItems(emptyList()), ref) } }
    }

    @Test
    fun `a user-mode stream should read one result per server-sent event`(): Unit = runBlocking {
        val accepted = WowJson.mapper.writeValueAsString(resultOf(ref, hop = Hop.accepted("mail")))
        val done = WowJson.mapper.writeValueAsString(committed())
        peer.reply(
            Reply.events(
                ": hello\n\nid: 0\nevent: accepted\ndata: $accepted\n",
                "\nid: 1\nevent: committed\n",
                "data: $done\n\n"
            )
        )
        val results = client(ClientMode.User(UserCredential.NONE)).stream(DeliverItems(emptyList()), ref).toList()
        results.map { it.hop.stage }.assert().containsExactly(Stage.ACCEPTED, Stage.COMMITTED)
        peer.taken.single().headers["Accept"].assert().isEqualTo("text/event-stream")
    }

    @Test
    fun `a stream answered with a problem should emit its result, or throw without one`(): Unit = runBlocking {
        val user = client(ClientMode.User(UserCredential.NONE))
        val refused = resultOf(
            ref,
            CommandResult.Status.REJECTED,
            ErrorInfo("wow.command.invalid", "No."),
            Hop.accepted()
        )
        peer.reply(Reply.problem(400, mapOf("code" to "wow.command.invalid", "result" to refused)))
        user.stream(
            DeliverItems(emptyList()),
            ref
        ).toList().single().status.assert().isEqualTo(CommandResult.Status.REJECTED)
        peer.reply(Reply.problem(403, mapOf("code" to "wow.auth.forbidden")))
        assertThrownBy<WowProblemException> { runBlocking { user.stream(DeliverItems(emptyList()), ref).toList() } }
        peer.reply(Reply.json(committed()))
        user.stream(
            DeliverItems(emptyList()),
            ref
        ).toList().single().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `a service-mode stream should be the one committed result`(): Unit = runBlocking {
        peer.reply(Reply.json(committed()))
        client().stream(DeliverItems(emptyList()), ref).toList().single().hop.stage.assert().isEqualTo(Stage.COMMITTED)
        peer.taken.single().headers["Accept"].assert().isEqualTo("application/json, application/problem+json")
        peer.reply(Reply.problem(403, mapOf("code" to "wow.auth.forbidden")))
        assertThrownBy<WowProblemException> { runBlocking { client().stream(DeliverItems(emptyList()), ref).toList() } }
    }

    private companion object {
        const val SERVICE = "basket-service"
    }
}
