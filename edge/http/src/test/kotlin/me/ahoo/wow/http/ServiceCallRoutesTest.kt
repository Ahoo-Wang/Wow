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

package me.ahoo.wow.http

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.subscribe.CallFailedException
import me.ahoo.wow.http.call.HttpCommandCaller
import me.ahoo.wow.http.call.ServiceCredential
import me.ahoo.wow.http.caller.ServiceTokens
import me.ahoo.wow.http.command.CallHeader
import me.ahoo.wow.http.internal.InternalServer
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.call.OutgoingCall
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/**
 * A service's synchronous call (decision D193): the client of another context's gateway, the service identity its
 * token makes it, and `Wow-Call`, which only the internal port's call routes take, from a service identity alone; the
 * public port refuses it whatever the credential.
 */
class ServiceCallRoutesTest {
    private val tokens = ServiceTokens(mapOf("basket-service" to listOf(TOKEN, ROTATED)), TENANT)
    private val node = HttpNode(callers = tokens.resolver(headerCallers))
    private val calls = callRoutes(cartManifest(), node.gateway, tokens.resolver(headerCallers))
    private val server = InternalServer(calls)
    private val internal: WebTestClient = WebTestClient.bindToRouterFunction(calls).build()
    private val caller = HttpCommandCaller(
        mapOf(CONTEXT to URI.create("http://${server.address}/")),
        ServiceCredential.bearer(TOKEN),
        timeout = 5.seconds,
    )

    @AfterEach
    fun close() {
        server.close()
    }

    private val root = Causation("root-1", "basket/acme/alice@2")

    private fun call(ref: String = "cart-1", body: Any = AddItem("mug"), id: String = "call-1"): OutgoingCall {
        val type = AggregateType(CONTEXT, "cart")
        val target = if (ref.isEmpty()) type.unassigned(TENANT) else type.ref(TENANT, ref)
        val command = Command(
            id = id,
            requestId = "request-$id",
            ref = target,
            body = body,
            scope = Scope("alice"),
            causation = root,
            operator = Operator("alice", TENANT),
        )
        return OutgoingCall("$type.${body.javaClass.simpleName}", command)
    }

    private fun callHeader(owner: String = "alice") = CallHeader.format(
        CallContext("call-x", TENANT, Scope(owner), root, Operator(owner, TENANT)),
    )

    @Test
    fun `a service's call should run as the root command's derived command`(): Unit = runBlocking {
        val result = caller.call(call())
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.stage.assert().isEqualTo(Stage.COMMITTED)
        result.commandId.assert().isEqualTo("call-1")
        val command = node.commands.single()
        command.id.assert().isEqualTo("call-1")
        command.requestId.assert().isEqualTo("request-call-1")
        command.scope.assert().isEqualTo(Scope("alice"))
        command.operator.assert().isEqualTo(Operator("alice", TENANT))
        command.causation.assert().isEqualTo(root)
    }

    @Test
    fun `a call that creates without an id should leave the id to the target`(): Unit = runBlocking {
        val created = caller.call(call(ref = "", body = OpenCart("hi")))
        created.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        created.ref.isAssigned.assert().isTrue()
        node.commands.single().ref.assert().isEqualTo(created.ref)
    }

    @Test
    fun `a call the target rejects should answer its rejected result`(): Unit = runBlocking {
        val rejected = caller.call(call(body = Checkout()))
        rejected.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        rejected.error!!.code.assert().isEqualTo(FrameworkErrors.AGGREGATE_NOT_FOUND)
    }

    @Test
    fun `a call with a token the target does not trust should fail`(): Unit = runBlocking {
        val untrusted = HttpCommandCaller(
            mapOf(CONTEXT to URI.create("http://${server.address}")),
            ServiceCredential.bearer("an-untrusted-token-of-at-least-32-characters"),
        )
        val failed = assertThrownBy<CallFailedException> { runBlocking { untrusted.call(call()) } }
        failed.assert().isNotNull()
        node.commands.assert().isEmpty()
    }

    /** `POST [path]` on the internal port's call routes with [headers]. */
    private fun internalPost(path: String, headers: Map<String, String>): Answer = internal.post().uri(path)
        .headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }
        .header("Content-Type", "application/json")
        .bodyValue("""{"sku":"mug"}""")
        .answer()

    private fun Answer.assertForbidden() {
        status.assert().isEqualTo(403)
        body!!["code"].asString().assert().isEqualTo(FrameworkErrors.AUTH_FORBIDDEN)
    }

    @Test
    fun `the public port should refuse Wow-Call whatever the credential`() {
        node.post("/shop/cart/cart-1/add-item", """{"sku":"mug"}""", "mallory", mapOf(WowHeaders.CALL to callHeader()))
            .assertForbidden()
        // Even the service's own token: a call is taken on the internal port alone.
        node.post(
            "/shop/cart/cart-1/add-item",
            """{"sku":"mug"}""",
            user = null,
            headers = mapOf("Authorization" to "Bearer $TOKEN", WowHeaders.CALL to callHeader()),
        ).assertForbidden()
        node.commands.assert().isEmpty()
    }

    @Test
    fun `the internal port should take a call from a service identity alone`() {
        val path = "/shop/cart/cart-1/add-item"
        // A buyer, a forged token and no credentials at all are no service.
        internalPost(path, mapOf("X-User" to "mallory", WowHeaders.CALL to callHeader())).assertForbidden()
        internalPost(
            path,
            mapOf("Authorization" to "Bearer $FORGED", WowHeaders.CALL to callHeader())
        ).assertForbidden()
        internalPost(path, mapOf(WowHeaders.CALL to callHeader())).assertForbidden()
        // A service's request that is no call is not taken either.
        internalPost(path, mapOf("Authorization" to "Bearer $TOKEN")).assertForbidden()
        node.commands.assert().isEmpty()
    }

    @Test
    fun `a service's Wow-Call that does not parse should be malformed`() {
        val answer = internalPost(
            "/shop/cart/cart-1/add-item",
            mapOf("Authorization" to "Bearer $ROTATED", WowHeaders.CALL to "%%%"),
        )
        answer.status.assert().isEqualTo(400)
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_MALFORMED)
    }

    @Test
    fun `a service's call should be answered as JSON even when it accepts an event stream`() {
        val answer = internalPost(
            "/shop/cart/cart-1/add-item",
            mapOf(
                "Authorization" to "Bearer $TOKEN",
                "Accept" to "text/event-stream",
                WowHeaders.CALL to callHeader(owner = "bob"),
            ),
        )
        answer.status.assert().isEqualTo(200)
        answer.body!!["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        node.commands.single().scope.assert().isEqualTo(Scope("bob"))
    }

    @Test
    fun `a service identity without a call should send a command of its own on the public port`() {
        val answer = node.post(
            "/shop/cart/cart-9/add-item",
            """{"sku":"mug"}""",
            user = null,
            headers = mapOf("Authorization" to "Bearer $TOKEN"),
        )
        answer.status.assert().isEqualTo(200)
        val command = node.commands.single()
        command.operator.assert().isEqualTo(Operator("service:basket-service", TENANT))
        command.causation.assert().isNull()
    }

    @Test
    fun `a call header should carry the root command and the wait`() {
        val wait = WaitContext("w-1", Origin("n-1", "127.0.0.1:9191", "basket"), listOf(Hop.committed(CONTEXT)), 9L)
        val call = CallContext("c-1", TENANT, Scope("alice", "s-1"), root, Operator("alice", TENANT, true), wait)
        CallHeader.parse(CallHeader.format(call)).assert().isEqualTo(call)
        val bare = CallContext("c-2", Caller.DEFAULT_TENANT, Scope.NONE, root)
        CallHeader.parse(" ${CallHeader.format(bare)} ").assert().isEqualTo(bare)
        assertThrownBy<IllegalArgumentException> { CallHeader.parse("%%%") }
        assertThrownBy<IllegalArgumentException> { CallHeader.parse("bm90LWpzb24") }
        // A call of a blank command id does not parse.
        val blank = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(
                """{"commandId":" ","tenantId":"t","causation":{"correlationId":"a","causationId":"b"}}""".toByteArray()
            )
        assertThrownBy<IllegalArgumentException> { CallHeader.parse(blank) }
    }

    private companion object {
        const val TOKEN = "basket-service-token-0123456789abcdef"
        const val ROTATED = "basket-service-next-token-0123456789"
        const val FORGED = "a-forged-token-of-at-least-32-characters"
    }
}
