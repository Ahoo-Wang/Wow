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

package me.ahoo.wow.example.eshop.ordering.server

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.testkit.IdentityHeader
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.wire.DevelopmentHeaders.ROLES
import me.ahoo.wow.api.wire.DevelopmentHeaders.USER
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.cosec.CoSecIdentity
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStateView
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * The ordering service as it is deployed, behind the CoSec gateway (the `cosec` profile): CoSec's gateway mode reads
 * the principal the gateway verified from its `CoSec-Identity` header, and wow-cosec's resolver makes it the caller. A
 * buyer owns the orders it submits and reads only its own; an employee, a tenant-level caller, reads every order; a
 * request without the header is anonymous and answered 401, whatever else it carries, a token included, since the
 * service verifies none. Outside development, a node without CoSec does not start: it would take its callers from
 * headers any client sets. The typed clients send as the gateway forwards a caller: its `CoSec-Identity`
 * ([GATEWAY]); a request without it goes over HTTP, its headers the point.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderingCoSecTest {
    private val server = wowServer<OrderingServer>("cosec", properties = ONLY_GATEWAY_CAN_REACH)

    @AfterAll
    fun close() {
        server.close()
    }

    /** The header the gateway forwards for [id] of the default tenant, with [roles], after verifying its token. */
    @OptIn(ExperimentalWowApi::class) // wow-cosec is experimental in 10.0
    private fun signedIn(id: String, vararg roles: String): Map<String, String> =
        CoSecIdentity(id, roles.toList()).headers()

    @Test
    @OptIn(ExperimentalWowApi::class) // wow-cosec is experimental in 10.0
    fun `the caller should be CoSec's`() {
        server.bean<CallerResolver>().assert().isInstanceOf(CoSecCallerResolver::class.java)
    }

    /** The credential the gateway forwards: the caller as the principal it verified, with the roles it has. */
    private val gateway = UserCredential { caller ->
        signedIn(caller.subject!!, *caller.attributes[Caller.ROLES].orEmpty().toTypedArray())
    }

    @Test
    fun `a buyer should own the order it submits, and an employee read it`() {
        val alice = Caller.of(subject = "alice")
        val items = listOf(OrderItem("mug", BigDecimal("8.50"), 2))
        runBlocking { server.commands(alice, gateway).wait(SubmitOrder(items), "cosec-alice-1") }
        read("cosec-alice-1", alice)!!.scope.ownerId.assert().isEqualTo("alice")
        read("cosec-alice-1", Caller.of(subject = "bob")).assert().isNull()
        read("cosec-alice-1", Caller.of(subject = "erin", attributes = mapOf(Caller.ROLES to listOf("employee"))))
            .assert().isNotNull()
    }

    @Test
    fun `a request without the gateway's identity should be answered 401, whatever headers it names`() {
        val headers = mapOf(USER to "mallory", ROLES to "employee", "Authorization" to "Bearer not-verified-here")
        val submitted = server.http.post(
            "/eshop-ordering/order/cosec-mallory-1/submit-order",
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2}]}""",
            headers,
        )
        submitted.problem!!.status.assert().isEqualTo(UNAUTHORIZED)
        server.http.get("/eshop-ordering/order/cosec-mallory-1/state", headers).problem!!.status
            .assert().isEqualTo(UNAUTHORIZED)
    }

    @Test
    @OptIn(ExperimentalWowApi::class) // wow-cosec is experimental in 10.0
    fun `an identity whose subject is blank should be refused 400 before the service, CoSec's IDN-38`() {
        val submitted = server.http.post(
            "/eshop-ordering/order/cosec-blank-1/submit-order",
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2}]}""",
            CoSecIdentity(" ").headers(),
        )
        submitted.status.assert().isEqualTo(BAD_REQUEST)
        submitted.body!!["type"].asString().assert().endsWith("invalid-request")
        // No command reached the service: an employee, who reads every order of the tenant, finds none.
        read("cosec-blank-1", Caller.of(subject = "erin", attributes = mapOf(Caller.ROLES to listOf("employee"))))
            .assert().isNull()
    }

    @Test
    fun `a node outside development without CoSec should not start`() {
        val failure = assertThrows<Exception> {
            wowServer<OrderingServer>(properties = mapOf("wow.internal.host" to "0.0.0.0")).close()
        }
        generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            .assert().contains("No durable backend", "A development profile")
    }

    private fun read(id: String, caller: Caller): Snapshot<OrderStateView>? =
        runBlocking { server.queries(caller, gateway).snapshots<OrderStateView>().state(id) }

    private companion object {
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
    }
}

/**
 * CoSec's statement that only its gateway reaches the node ([IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY]), which the
 * gateway mode of the `cosec` profile asks of every host: a test is reached by its own client alone. A deployment
 * states it with the wow-service chart's NetworkPolicy.
 */
@OptIn(ExperimentalCoSecApi::class) // CoSec's test kit is experimental in 8.0.
private val ONLY_GATEWAY_CAN_REACH: Map<String, Any> = mapOf(IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY to "true")
