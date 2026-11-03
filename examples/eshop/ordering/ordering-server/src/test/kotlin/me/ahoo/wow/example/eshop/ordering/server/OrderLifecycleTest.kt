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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.example.eshop.ordering.api.order.ConfirmOrderStock
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStateView
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

/**
 * The lifecycle routes the Manifest lists for every aggregate (decision D113), on the ordering's orders, which belong
 * to the buyers who submitted them (decision D189): an order deleted and restored, an order moved to another buyer's
 * account, and an order tagged and moved into a fulfilment space. The lifecycle commands go over HTTP, to the routes
 * this test is about; the orders are submitted, confirmed and read by the typed clients.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderLifecycleTest {
    private val server = wowServer<OrderingServer>("dev")

    @AfterAll
    fun close() {
        server.close()
    }

    @Test
    fun `a deleted order should refuse its next command until it is restored`() {
        val alice = buyer()
        val id = submitted(alice)
        val deleted = server.http.request("DELETE", "/eshop-ordering/order/$id", headers = headersOf(alice))
        deleted.problem.assert().describedAs(deleted.text).isNull()
        deleted.body!!["version"].asInt().assert().isEqualTo(VERSION_AFTER_VALIDATION + 1)
        read(id, alice)!!.deleted.assert().isTrue()

        rejected { confirm(id, alice) }.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_DELETED)

        lifecycle(id, "restore-aggregate", headersOf(alice)).problem.assert().isNull()
        read(id, alice)!!.deleted.assert().isFalse()
        confirm(id, alice)
    }

    @Test
    fun `an order moved to another buyer's account should be that buyer's alone`() {
        val second = buyer()
        val main = buyer()
        val id = submitted(second)
        val transfer = """{"ownerId":"${main.subject}"}"""
        lifecycle(id, "transfer-owner", headersOf(second), transfer).problem.assert().isNull()
        read(id, main)!!.scope.ownerId.assert().isEqualTo(main.subject)

        // The former owner neither reads nor changes it: its state is not found for it (decision D254 ④), and the
        // kernel's scope guard refuses its commands (decision D94).
        read(id, second).assert().isNull()
        rejected { confirm(id, second) }.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        confirm(id, main)
    }

    @Test
    fun `an employee should tag an order and move it into a fulfilment space`() {
        val alice = buyer()
        val id = submitted(alice)
        lifecycle(id, "apply-tags", EMPLOYEE_HEADERS, """{"tags":{"channel":["web"]}}""").problem.assert().isNull()
        lifecycle(id, "transfer-space", EMPLOYEE_HEADERS, """{"spaceId":"warehouse-east"}""").problem.assert().isNull()
        val order = read(id, EMPLOYEE)!!
        order.tags["channel"].assert().isEqualTo(listOf("web"))
        order.scope.spaceId.assert().isEqualTo("warehouse-east")
        // The order is still its buyer's.
        order.scope.ownerId.assert().isEqualTo(alice.subject)
    }

    /** A buyer of this test only: the user, and the owner of what it creates. */
    private fun buyer(): Caller =
        ("buyer-" + UUID.randomUUID().toString().take(8)).let { Caller.of(subject = it, scope = Scope(ownerId = it)) }

    /** The development identity headers of [buyer], for the lifecycle routes. */
    private fun headersOf(buyer: Caller): Map<String, String> =
        mapOf(DevelopmentHeaders.USER to buyer.subject!!, DevelopmentHeaders.OWNER to buyer.subject!!)

    /** Submits an order as [buyer] and waits until the order process has it await its stock validation; its id. */
    private fun submitted(buyer: Caller): String {
        val id = "order-" + UUID.randomUUID()
        val items = listOf(OrderItem("mug", BigDecimal("8.50"), 2), OrderItem("cap", BigDecimal("12.00"), 1))
        val request = CommandRequest.builder(SubmitOrder(items)).id(id).wait(TO_VALIDATION).build()
        runBlocking { server.commands(buyer).wait(request) }
        return id
    }

    /** Sends the lifecycle command [kebab] (`wow.lifecycle.<kebab>`) to the order [id], over its route. */
    private fun lifecycle(id: String, kebab: String, headers: Map<String, String>, body: String = "{}"): Answer =
        server.http.post("/eshop-ordering/order/$id/wow.lifecycle.$kebab", body, headers)

    private fun confirm(id: String, caller: Caller) {
        runBlocking { server.commands(caller).wait(ConfirmOrderStock("mug"), id) }
    }

    private fun read(id: String, caller: Caller): Snapshot<OrderStateView>? =
        runBlocking { server.queries(caller).snapshots(OrderStateView::class.java).state(id) }

    private fun rejected(send: () -> Unit): CommandRejectedException = assertThrows<CommandRejectedException> { send() }

    private companion object {
        /** The order's version once the order process had it await its validation: submitted, then awaiting. */
        const val VERSION_AFTER_VALIDATION = 2
        val EMPLOYEE = Caller.of(
            subject = "erin",
            attributes = mapOf(DevelopmentHeaders.ROLES_ATTRIBUTE to listOf("employee")),
        )
        val EMPLOYEE_HEADERS = mapOf(DevelopmentHeaders.USER to "erin", DevelopmentHeaders.ROLES to "employee")
        val TO_VALIDATION: WaitPlan = WaitPlan.of(
            Hop.handled("OrderProcessSaga.onOrderSubmitted", EshopOrderingContract.NAME),
            Hop.snapshot(EshopOrderingContract.NAME),
        )
    }
}
