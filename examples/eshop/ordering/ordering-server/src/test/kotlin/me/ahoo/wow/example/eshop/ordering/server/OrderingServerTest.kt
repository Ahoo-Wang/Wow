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

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.stream
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.example.eshop.ordering.api.order.ConfirmOrderStock
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStateView
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import me.ahoo.wow.example.eshop.ordering.api.order.PayOrder
import me.ahoo.wow.example.eshop.ordering.api.order.ShipOrder
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.test.server.assertRejected
import me.ahoo.wow.test.server.eventually
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The ordering service as its `application.yaml` configures it, on random ports, with a payment grace period of two
 * seconds, driven by its typed clients. No inventory runs: the test confirms the order's products itself, as the
 * stock-confirmation saga would once it read the reservations from the inventory's stream.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderingServerTest {
    // region start
    private val server = wowServer<OrderingServer>(
        "dev",
        properties = mapOf(
            "wow.timers.poll-interval" to "200ms",
            "eshop.ordering.payment-grace-period" to "2s",
        ),
    )
    // endregion

    @AfterAll
    fun close() {
        server.close()
    }

    @Test
    fun `the server should run one node of the ordering context`() {
        val node = server.bean<ServiceNode>()
        node.service.assert().isEqualTo("eshop-ordering-service")
        node.isReady.assert().isTrue()
        node.hostedContexts.assert().containsExactly("eshop-ordering")
        server.bean<Manifest>().contexts.single().aggregates.map { it.name }.assert().containsExactly("order")
    }

    @Test
    fun `a submitted order should await validation, take its confirmations, its payment and its shipment`() {
        submit("order-paid")
        order("order-paid").status.assert().isEqualTo(OrderStatus.AWAITING_VALIDATION)
        confirm("order-paid", "mug")
        order("order-paid").status.assert().isEqualTo(OrderStatus.AWAITING_VALIDATION)
        confirm("order-paid", "cap")
        order("order-paid").status.assert().isEqualTo(OrderStatus.STOCK_CONFIRMED)

        assertRejected { orders().wait(PayOrder("p-1", BigDecimal.ONE), "order-paid") }
            .code.assert().isEqualTo("eshop-ordering.order.wrong-amount")
        runBlocking { orders().wait(PayOrder("p-1", BigDecimal("29.00")), "order-paid") }
        runBlocking { orders(EMPLOYEE).wait(ShipOrder, "order-paid") }
        order("order-paid").status.assert().isEqualTo(OrderStatus.SHIPPED)
        // The grace period has long passed, and the paid order kept its status.
        Thread.sleep(GRACE_AND_POLL.toMillis())
        order("order-paid").status.assert().isEqualTo(OrderStatus.SHIPPED)
    }

    // region grace
    @Test
    fun `an order not paid within its grace period should cancel itself`() {
        submit("order-unpaid")
        confirm("order-unpaid", "mug", "cap")
        order("order-unpaid").status.assert().isEqualTo(OrderStatus.STOCK_CONFIRMED)
        // The order cancels itself by a scheduled command of its own, which no command of the test's waits for.
        eventually("order-unpaid cancelled") { order("order-unpaid").status == OrderStatus.CANCELLED }
        order("order-unpaid").cancelReason.assert().contains("payment grace period")
        assertRejected { orders().wait(PayOrder("p-2", BigDecimal("29.00")), "order-unpaid") }
            .code.assert().isEqualTo("eshop-ordering.order.wrong-status")
    }
    // endregion

    @Test
    fun `an order should be its buyer's to read, and every order an employee's`() {
        submit("order-alice", buyer("alice"))
        read("order-alice", buyer("alice"))!!.scope.ownerId.assert().isEqualTo("alice")
        // Another buyer's order is not found for it (decision D254 ④).
        read("order-alice", buyer("bob")).assert().isNull()
        read("order-alice", EMPLOYEE).assert().isNotNull()
        read("order-alice", Caller.ANONYMOUS).assert().isNotNull()
    }

    /** Over HTTP: how the development callers read a blank `Wow-User` is the header's own contract. */
    @Test
    fun `a blank user should be no user, the anonymous caller of the whole tenant`() {
        submit("order-blank", buyer("alice-blank"))
        val read = server.http.get("/eshop-ordering/order/order-blank/state", mapOf(DevelopmentHeaders.USER to " "))
        read.problem.assert().describedAs(read.text).isNull()
    }

    // region numbers
    @Test
    fun `an order submitted without an id should be numbered once per request (D373, D191)`() {
        val before = LocalDate.now(ZoneOffset.UTC)
        val first = submitNew("checkout-1")
        val after = LocalDate.now(ZoneOffset.UTC)
        // SO-<yyMMdd>-<sequence>: the day it was issued, in UTC, then the sequence.
        val number = NUMBER.matchEntire(first.ref.id).assert().isNotNull().actual()!!
        number.groupValues[1].assert().isIn(before.format(DAY), after.format(DAY))
        first.duplicate.assert().isFalse()

        // The same request again, as a retried or replayed checkout sends it: the same order and number, no other.
        val retried = submitNew("checkout-1")
        retried.ref.id.assert().isEqualTo(number.value)
        retried.duplicate.assert().isTrue()

        // Another request is another order, with a later number.
        val next = NUMBER.matchEntire(submitNew("checkout-2").ref.id).assert().isNotNull().actual()!!
        next.groupValues[2].toLong().assert().isGreaterThan(number.groupValues[2].toLong())
        // The number is the order's id: the order is read by it.
        read(number.value, buyer("alice-numbers")).assert().isNotNull()
    }

    /** Submits an order that names no id, as the basket's checkout does, with the request id [requestId]. */
    private fun submitNew(requestId: String): CommandResult {
        return runBlocking { orders(buyer("alice-numbers")).wait(SubmitOrder(listOf(MUGS))) { requestId(requestId) } }
    }
    // endregion

    @Test
    fun `an order without items, or with an invalid one, should be invalid before the order decides`() {
        val empty = assertRejected { orders().wait(SubmitOrder(emptyList()), "order-empty") }
        empty.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
        empty.violations.single().let {
            it.path.assert().isEqualTo("items")
            it.code.assert().isEqualTo("not-empty")
        }

        val item = OrderItem("mug", BigDecimal("-1"), 0)
        val invalid = assertRejected { orders().wait(SubmitOrder(listOf(item)), "order-invalid") }
        invalid.violations.map { it.path to it.code }
            .assert().containsExactly("items[0].quantity" to "positive", "items[0].unitPrice" to "positive-or-zero")
        read("order-invalid", Caller.ANONYMOUS).assert().isNull()
    }

    @Test
    fun `only an employee should ship a paid order, not its buyer (UI-49)`() {
        val alice = buyer("alice-ships")
        submit("order-shipped", alice)
        confirm("order-shipped", "mug", "cap", caller = alice)
        runBlocking { orders(alice).wait(PayOrder("p-2", BigDecimal("29.00")), "order-shipped") }
        val refused = assertRejected { orders(alice).wait(ShipOrder, "order-shipped") }
        refused.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        refused.error.message.assert().contains("Only an employee ships an order")
        order("order-shipped").status.assert().isEqualTo(OrderStatus.PAID)
        runBlocking { orders(EMPLOYEE).wait(ShipOrder, "order-shipped") }
        order("order-shipped").status.assert().isEqualTo(OrderStatus.SHIPPED)
    }

    /**
     * Over HTTP, through the rebuild's route, as an operator's tool sends it: the route's own authorization is what
     * the test holds.
     */
    @Test
    fun `the snapshot rebuild should be an employee's alone (UI-50)`() {
        val rebuild = "/eshop-ordering/order/wow.snapshot.rebuild-snapshot"
        for (caller in listOf(Caller.ANONYMOUS, buyer("alice-rebuilds"))) {
            val refused = server.http.post(rebuild, """{"ids":["order-1"]}""", caller = caller).problem!!
            refused.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
            refused.detail.assert().contains("Only an employee sends wow.snapshot.RebuildSnapshot")
        }
        server.http.post(rebuild, """{"ids":["order-1"]}""", caller = EMPLOYEE).problem.assert().isNull()
    }

    /**
     * A public request that names a service in every header it can is no service identity, so the operational
     * authorizer decides it: the development callers read no header as one and the public port reads no
     * `Wow-Service`. Were it a service identity, `CommandAuthorizer.operational` would not ask, and the ordering's
     * domain authorizer, which objects to `ShipOrder` alone, would let a buyer rebuild (#6662).
     */
    @Test
    fun `a public request naming a service in every header is still an employee's to rebuild`() {
        val service = "eshop-basket-service"
        val named = server.http.post(
            "/eshop-ordering/order/wow.snapshot.rebuild-snapshot",
            """{"ids":["order-1"]}""",
            headers = mapOf(
                WowHeaders.SERVICE to service,
                DevelopmentHeaders.USER to Caller.SERVICE_SUBJECT_PREFIX + service,
                DevelopmentHeaders.OWNER to Caller.SERVICE_SUBJECT_PREFIX + service,
                DevelopmentHeaders.ROLES to "service",
            ),
        ).problem!!
        named.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        named.detail.assert().contains("Only an employee sends wow.snapshot.RebuildSnapshot")
    }

    /**
     * What the contract generates and what the node accepts, decided by the contract generator and by the runtime's
     * wait chain, held to each other: every hop of the generated `EshopOrderingContract.Wait` is one this node's
     * admission accepts. The order-process saga has `@OnEvent`, `@OnCommitted` and `@OnRejected` handlers.
     */
    @Test
    fun `every hop the contract generates should be one the node waits for`() {
        val hops = EshopOrderingContract.Wait::class.java.fields
            .filter { it.type == Hop::class.java }
            .associate { it.name to it.get(null) as Hop }
        val refused = hops.mapNotNull { (name, hop) ->
            val first = runBlocking {
                orders().stream(SubmitOrder(listOf(MUGS)), "hop-${UUID.randomUUID()}") {
                    wait(WaitPlan.of(hop).within(Duration.ofMillis(HOP_TIMEOUT)))
                }.first()
            }
            "$name: ${first.error?.message}".takeIf { first.error?.code == FrameworkCodes.WAIT_INVALID }
        }
        refused.assert().isEmpty()
    }

    private fun orders(caller: Caller = Caller.ANONYMOUS): CommandClient = server.commands(caller)

    /** Submits the order [id] as [caller] and waits until the order-process saga set it awaiting its validation. */
    private fun submit(id: String, caller: Caller = Caller.ANONYMOUS) {
        runBlocking { orders(caller).wait(SubmitOrder(listOf(MUGS, CAP)), id) { wait(TO_VALIDATION) } }
    }

    /** Confirms each of [products] of the order [id], as the stock-confirmation saga would. */
    private fun confirm(id: String, vararg products: String, caller: Caller = Caller.ANONYMOUS) = runBlocking {
        products.forEach { orders(caller).wait(ConfirmOrderStock(it), id) }
    }

    /** The order [id] as [caller] reads it; null when it sees none. */
    private fun read(id: String, caller: Caller): Snapshot<OrderStateView>? =
        runBlocking { server.queries(caller).snapshots<OrderStateView>().state(id) }

    private fun order(id: String): OrderStateView = read(id, Caller.ANONYMOUS)!!.state

    private companion object {
        val MUGS = OrderItem("mug", BigDecimal("8.50"), 2)
        val CAP = OrderItem("cap", BigDecimal("12.00"), 1)
        val EMPLOYEE = Caller.of(
            subject = "erin",
            attributes = mapOf(Caller.ROLES to listOf("employee")),
        )

        // region wait-plan
        /** A submission's plan to its chain's end: the order-process saga set the order awaiting its validation. */
        val TO_VALIDATION: WaitPlan = WaitPlan.of(
            EshopOrderingContract.Wait.ORDER_PROCESS_SAGA__ON_ORDER_SUBMITTED,
            EshopOrderingContract.Wait.SNAPSHOT,
        )
        // endregion

        val GRACE_AND_POLL: Duration = Duration.ofMillis(3_000)

        /** How long a hop's wait lasts: only its admission is read. */
        const val HOP_TIMEOUT = 500L

        /** An order's number: `SO-`, the day it was issued (`yyMMdd`), `-`, its sequence. */
        val NUMBER = Regex("""SO-(\d{6})-(\d+)""")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd")

        /** The buyer [id]: the user, and the owner of what it creates. */
        fun buyer(id: String): Caller = Caller.owner(id)
    }
}
