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
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.client.HttpCommandCaller
import me.ahoo.wow.http.caller.CallPermits
import me.ahoo.wow.http.caller.DeclaredServices
import me.ahoo.wow.http.internal.InternalServer
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.spi.call.OutgoingCall
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/**
 * A service's synchronous call (decision D193): the client of another context's gateway, the service it names
 * (`Wow-Service`, decision D300), and `Wow-Call`, which only the internal port's call routes take, from a service that
 * names itself alone; the public port refuses it whatever the request carries.
 */
class ServiceCallRoutesTest {
    private val services = DeclaredServices.resolver(TENANT)
    private val node = HttpNode(callers = headerCallers)
    private val calls = callRoutes(cartManifest(), node.gateway, services)
    private val server = InternalServer(calls)
    private val internal: WebTestClient = WebTestClient.bindToRouterFunction(calls).build()
    private val caller = HttpCommandCaller(
        mapOf(CONTEXT to URI.create("http://${server.address}/")),
        SERVICE,
        timeout = 5.seconds,
    )

    @AfterEach
    fun close() {
        server.close()
    }

    private val root = Causation("root-1", "basket/acme/alice@2")

    private fun call(
        ref: String = "cart-1",
        body: Any = AddItem("mug"),
        id: String = "call-1",
        headers: Map<String, String> = emptyMap(),
    ): OutgoingCall {
        val type = AggregateType(CONTEXT, "cart")
        val target = if (ref.isEmpty()) type.unassigned(TENANT) else type.ref(TENANT, ref)
        val command = Command(
            id = id,
            requestId = "request-$id",
            ref = target,
            body = body,
            scope = Scope("alice"),
            headers = headers,
            causation = root,
            operator = Operator("alice", TENANT),
        )
        return OutgoingCall("$type.${body.javaClass.simpleName}", command)
    }

    private fun callHeader(owner: String = "alice", facts: Map<String, String> = emptyMap()) = CallHeaders.format(
        CallContext("call-x", TENANT, Scope(owner), root, Operator(owner, TENANT), facts = facts),
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
        // The internal port's peer is the calling service, not a client: no default request fact (09 §17 G-10).
        command.headers.keys.assert().doesNotContain("remote_ip", "user_agent")
    }

    @Test
    fun `a service's call should carry the root request's application to the target (D236)`(): Unit = runBlocking {
        // The saga's command inherited the root request's propagating facts; the others never travel.
        val inherited = mapOf(
            RequestFacts.APP_ID to "console",
            RequestFacts.REMOTE_IP to "203.0.113.9",
            RequestFacts.USER_AGENT to "curl",
            "X-Trace" to "t-1",
        )
        caller.call(call(headers = inherited)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        node.commands.single().headers.assert().isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
    }

    @Test
    fun `a forged application should not reach the target's command (D236)`() {
        val path = "/shop/cart/cart-1/add-item"
        val forged = mapOf(RequestFacts.APP_ID to "forged")
        // A caller that is no service identity is refused, its call's facts with it.
        internalPost(path, mapOf("X-User" to "mallory", WowHeaders.CALL to callHeader(facts = forged)))
            .assertForbidden()
        node.commands.assert().isEmpty()
        // A service's call carries the root request's application in its Wow-Call alone: no header sets it.
        internalPost(
            path,
            mapOf(WowHeaders.SERVICE to SERVICE, WowHeaders.CALL to callHeader(), RequestFacts.APP_ID to "forged"),
        ).status.assert().isEqualTo(200)
        node.commands.single().headers.keys.assert().doesNotContain(RequestFacts.APP_ID)
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
    fun `a call of a command the target does not decide should answer a rejection, not a failure (X-5)`(): Unit =
        runBlocking {
            // The caller's contract is newer than the target: a command the target has no route of.
            val byId = caller.call(call().let { OutgoingCall("$CONTEXT.cart.AddItemV2", it.command) })
            byId.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            byId.error!!.code.assert().isEqualTo(FrameworkErrors.COMMAND_UNHANDLED)
            byId.commandId.assert().isEqualTo("call-1")
            val creation = caller.call(call(ref = "").let { OutgoingCall("$CONTEXT.cart.OpenCartV2", it.command) })
            creation.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            creation.error!!.code.assert().isEqualTo(FrameworkErrors.COMMAND_UNHANDLED)
            val wishlist = call().command.copyWith(ref = AggregateType(CONTEXT, "wishlist").ref(TENANT, "w-1"))
            val aggregate = caller.call(OutgoingCall("$CONTEXT.wishlist.AddItem", wishlist))
            aggregate.error!!.code.assert().isEqualTo(FrameworkErrors.COMMAND_UNHANDLED)
            node.commands.assert().isEmpty()
            // Still a service's call alone: any other caller is refused first.
            internalPost("/shop/cart/cart-1/add-item-v2", mapOf("X-User" to "mallory")).assertForbidden()
        }

    @Test
    fun `a call that names no service should fail`(): Unit = runBlocking {
        val untrusted = HttpCommandCaller(mapOf(CONTEXT to URI.create("http://${server.address}")), service = null)
        val failed = assertThrownBy<WowProblemException> { runBlocking { untrusted.call(call()) } }
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
    fun `the public port should refuse Wow-Call whatever the request carries`() {
        node.post("/shop/cart/cart-1/add-item", """{"sku":"mug"}""", "mallory", mapOf(WowHeaders.CALL to callHeader()))
            .assertForbidden()
        // Even naming a service: a call is taken on the internal port alone.
        node.post(
            "/shop/cart/cart-1/add-item",
            """{"sku":"mug"}""",
            user = "alice",
            headers = mapOf(WowHeaders.SERVICE to SERVICE, WowHeaders.CALL to callHeader()),
        ).assertForbidden()
        node.commands.assert().isEmpty()
    }

    @Test
    fun `the internal port should take a call from a service that names itself alone`() {
        val path = "/shop/cart/cart-1/add-item"
        // A buyer, a value that is no service name, and a request that names none are no service.
        internalPost(path, mapOf("X-User" to "mallory", WowHeaders.CALL to callHeader())).assertForbidden()
        internalPost(path, mapOf(WowHeaders.SERVICE to "basket service", WowHeaders.CALL to callHeader()))
            .assertForbidden()
        internalPost(path, mapOf(WowHeaders.CALL to callHeader())).assertForbidden()
        // A service's request that is no call is not taken either.
        internalPost(path, mapOf(WowHeaders.SERVICE to SERVICE)).assertForbidden()
        node.commands.assert().isEmpty()
    }

    @Test
    fun `a service's Wow-Call that does not parse should be malformed`() {
        val answer = internalPost(
            "/shop/cart/cart-1/add-item",
            mapOf(WowHeaders.SERVICE to SERVICE, WowHeaders.CALL to "%%%"),
        )
        answer.status.assert().isEqualTo(400)
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_MALFORMED)
    }

    @Test
    fun `a service's call should be answered as JSON even when it accepts an event stream`() {
        val answer = internalPost(
            "/shop/cart/cart-1/add-item",
            mapOf(
                WowHeaders.SERVICE to SERVICE,
                "Accept" to "text/event-stream",
                WowHeaders.CALL to callHeader(owner = "bob"),
            ),
        )
        answer.status.assert().isEqualTo(200)
        answer.body!!["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        node.commands.single().scope.assert().isEqualTo(Scope("bob"))
    }

    @Test
    fun `the public port should take no service's name for an identity (D300)`() {
        val answer = node.post(
            "/shop/cart/cart-9/add-item",
            """{"sku":"mug"}""",
            user = null,
            headers = mapOf(WowHeaders.SERVICE to SERVICE),
        )
        answer.status.assert().isEqualTo(401)
        node.commands.assert().isEmpty()
    }

    /** The internal port's call routes, with [permits], called by the basket service with its call. */
    private fun permitted(permits: CallPermits): WebTestClient =
        WebTestClient.bindToRouterFunction(
            callRoutes(cartManifest(), node.gateway, services, permits = permits)
        )
            .build()

    private fun WebTestClient.call(method: String, path: String, body: String? = """{"sku":"mug"}"""): Answer =
        method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
            .header(WowHeaders.SERVICE, SERVICE)
            .header(WowHeaders.CALL, callHeader())
            .header("Content-Type", "application/json")
            .let { if (body == null) it else it.bodyValue(body) }
            .answer()

    private fun Answer.assertCommandForbidden() {
        status.assert().isEqualTo(403)
        body!!["code"].asString().assert().isEqualTo(CallPermits.COMMAND_FORBIDDEN)
    }

    @Test
    fun `a service's call of a system command should be refused unless its commands list it (D226)`() {
        // By default: the domain commands alone.
        val defaults = permitted(CallPermits(emptyMap()))
        defaults.call("DELETE", "/shop/cart/cart-1", body = null).assertCommandForbidden()
        defaults.call("POST", "/shop/cart/cart-1/wow.lifecycle.transfer-owner", """{"ownerId":"mallory"}""")
            .assertCommandForbidden()
        defaults.call("POST", "/shop/cart/cart-1/wow.lifecycle.apply-tags", """{"tags":{}}""").assertCommandForbidden()
        // A wildcard alone opens no system command.
        permitted(CallPermits(mapOf("basket-service" to listOf("*")))).call("DELETE", "/shop/cart/cart-1", body = null)
            .assertCommandForbidden()
        node.commands.assert().isEmpty()
        // Listed, the system command is the gateway's: this cart does not exist.
        val listed = permitted(CallPermits(mapOf("basket-service" to listOf("wow.lifecycle.DeleteAggregate"))))
        listed.call("DELETE", "/shop/cart/cart-1", body = null).status.assert().isEqualTo(404)
        listed.call("POST", "/shop/cart/cart-1/wow.lifecycle.restore-aggregate", body = null).assertCommandForbidden()
        // The domain commands stay allowed beside a listed system command.
        listed.call("POST", "/shop/cart/cart-1/add-item").status.assert().isEqualTo(200)
    }

    @Test
    fun `a service's commands should narrow its domain commands to an allowlist (D226)`() {
        val narrowed = permitted(CallPermits(mapOf("basket-service" to listOf("shop.cart.Checkout", "Open*"))))
        narrowed.call("POST", "/shop/cart/cart-1/add-item").assertCommandForbidden()
        node.commands.assert().isEmpty()
        narrowed.call("POST", "/shop/cart/open-cart", """{"note":"hi"}""").status.assert().isEqualTo(200)
        // Another service's list is not this one's: the gateway decides (the call's command id is taken already).
        permitted(
            CallPermits(mapOf("ordering-service" to listOf("Checkout")))
        ).call("POST", "/shop/cart/cart-1/add-item")
            .body!!["code"].asString().assert().isNotEqualTo(CallPermits.COMMAND_FORBIDDEN)
    }

    @Test
    fun `a call header should carry the root command and the wait`() {
        val wait = WaitContext("w-1", Origin("n-1", "127.0.0.1:9191", "basket"), listOf(Hop.committed(CONTEXT)), 9L)
        val call = CallContext("c-1", TENANT, Scope("alice", "s-1"), root, Operator("alice", TENANT, true), wait)
        CallHeaders.parse(CallHeaders.format(call)).assert().isEqualTo(call)
        val bare = CallContext("c-2", Caller.DEFAULT_TENANT, Scope.NONE, root)
        CallHeaders.parse(" ${CallHeaders.format(bare)} ").assert().isEqualTo(bare)
        val withFacts =
            CallContext("c-3", TENANT, Scope("alice"), root, facts = mapOf(RequestFacts.APP_ID to "console"))
        CallHeaders.parse(
            CallHeaders.format(withFacts)
        ).facts.assert().isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
        // Only the facts that propagate are taken from the header, whatever a caller puts in it.
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val stuffed = encoder.encodeToString(
            (
                """{"commandId":"c","tenantId":"t","causation":{"correlationId":"a","causationId":"b"},""" +
                    """"facts":{"app_id":"console","remote_ip":"10.0.0.1","user_agent":"x","X-Trace":"t"}}"""
                ).toByteArray()
        )
        CallHeaders.parse(stuffed).facts.assert().isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
        assertThrownBy<IllegalArgumentException> { CallHeaders.parse("%%%") }
        assertThrownBy<IllegalArgumentException> { CallHeaders.parse("bm90LWpzb24") }
        // A call of a blank command id does not parse.
        val blank = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(
                """{"commandId":" ","tenantId":"t","causation":{"correlationId":"a","causationId":"b"}}""".toByteArray()
            )
        assertThrownBy<IllegalArgumentException> { CallHeaders.parse(blank) }
    }

    private companion object {
        const val SERVICE = "basket-service"
    }
}
