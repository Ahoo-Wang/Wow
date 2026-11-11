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

package me.ahoo.wow.example.eshop.basket.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.example.eshop.basket.api.basket.AddBasketItem
import me.ahoo.wow.example.eshop.basket.api.basket.BasketStateView
import me.ahoo.wow.example.eshop.basket.api.basket.ChangeBasketItemQuantity
import me.ahoo.wow.example.eshop.basket.api.basket.CheckoutBasket
import me.ahoo.wow.example.eshop.basket.api.basket.EshopBasketContract
import me.ahoo.wow.example.eshop.basket.api.basket.RemoveBasketItem
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.test.server.fakePeer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * The basket service as its `application.yaml` configures it, on random ports, driven by its typed clients, the
 * ordering service a fake that records the checkout saga's calls (decision D193). The callers are the host's in
 * development: a buyer is the user and the owner, and owns what it creates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BasketServerTest {
    private val ordering = fakePeer("eshop-ordering")
    private val server = wowServer<BasketServer>("dev", peers = listOf(ordering))

    @AfterAll
    fun close() {
        server.close()
        ordering.close()
    }

    @Test
    fun `the server should run one node of the basket context`() {
        val node = server.bean<ServiceNode>()
        node.service.assert().isEqualTo("eshop-basket-service")
        node.isReady.assert().isTrue()
        node.hostedContexts.assert().containsExactly("eshop-basket")
        server.bean<Manifest>().contexts.single().aggregates.map { it.name }.assert().containsExactly("basket")
    }

    @Test
    fun `a buyer should fill their basket and check it out into an order`() {
        // The buyer's commands name no basket: its basket is the one whose id is the buyer's.
        val alice = server.commands(buyer("alice"))
        runBlocking {
            alice.wait(AddBasketItem("mug", BigDecimal("8.50"), 2)).ref.id.assert().isEqualTo("alice")
            alice.wait(AddBasketItem("cap", BigDecimal("12.00"), 1))
            alice.wait(ChangeBasketItemQuantity("cap", 3))
            alice.wait(RemoveBasketItem("cap"))
            alice.wait(CheckoutBasket)
        }
        basket("alice", buyer("alice"))!!.state.items.assert().isEmpty()
        // The saga calls the ordering service's internal port, naming itself, for the buyer: the order belongs to the
        // buyer, the call carrying the basket's scope and the buyer as its operator (decisions D163, D189, D193). It
        // names no order: the ordering numbers the order it creates (decision D373).
        val call = ordering.called<SubmitOrder>()
        call.service.assert().isEqualTo("eshop-basket-service")
        call.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        call.operator?.id.assert().isEqualTo("alice")
        call.aggregateId.assert().isNull()
        (call.command as SubmitOrder).items.single().productId.assert().isEqualTo("mug")

        rejected { alice.wait(CheckoutBasket) }.code.assert().isEqualTo(EshopBasketContract.Basket.Errors.EMPTY)
    }

    @Test
    fun `a basket should be its buyer's alone`() {
        val dave = server.commands(buyer("dave"))
        rejected { dave.wait(AddBasketItem("mug", BigDecimal("8.50"), 1), "carol") }
            .code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        // By its own id, the buyer reaches the basket the commands without one do.
        runBlocking { dave.wait(AddBasketItem("mug", BigDecimal("8.50"), 1), "dave") }
        basket("dave", buyer("dave"))!!.state.items.single().productId.assert().isEqualTo("mug")
    }

    @Test
    fun `an item of no quantity or a negative price should be invalid before the basket decides`() {
        val frank = server.commands(buyer("frank"))
        val invalid = rejected { frank.wait(AddBasketItem("mug", BigDecimal("-1"), 0)) }
        invalid.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
        invalid.result.error!!.violations.map { it.path to it.code }
            .assert().containsExactly("quantity" to "positive", "unitPrice" to "positive-or-zero")
        // Nothing was decided: the basket was not created.
        basket("frank", buyer("frank")).assert().isNull()

        runBlocking { frank.wait(AddBasketItem("mug", BigDecimal("8.50"), 1)) }
        rejected { frank.wait(ChangeBasketItemQuantity("mug", 0)) }.result.error!!.violations.single()
            .path.assert().isEqualTo("quantity")
    }

    @Test
    fun `an employee should read every buyer's basket by its id, and the anonymous caller too`() {
        runBlocking { server.commands(buyer("erin-buyer")).wait(AddBasketItem("mug", BigDecimal("8.50"), 1)) }
        basket("erin-buyer", EMPLOYEE).assert().isNotNull()
        basket("erin-buyer", Caller.ANONYMOUS).assert().isNotNull()
        // Another buyer's basket is not found for it: what a caller may not see is not found (decision D254 ④).
        basket("erin-buyer", buyer("dave")).assert().isNull()
    }

    /** Over HTTP: the read route that names no basket, the caller's own, is a route the typed queries do not take. */
    @Test
    fun `an employee should have no basket of its own to read by the route that names none`() {
        val noOwner = server.http.get("/eshop-basket/basket/state", caller = EMPLOYEE)
        noOwner.problem!!.status.assert().describedAs(noOwner.text).isEqualTo(BAD_REQUEST)
        noOwner.problem!!.code.assert().isEqualTo(FrameworkCodes.QUERY_MALFORMED)
    }

    /** The basket [id] as [caller] reads it; null when the caller sees none. */
    private fun basket(id: String, caller: Caller): Snapshot<BasketStateView>? =
        runBlocking { server.queries(caller).snapshots(BasketStateView::class.java).state(id) }

    private fun rejected(send: suspend () -> Unit): CommandRejectedException =
        assertThrows<CommandRejectedException> { runBlocking { send() } }

    private companion object {
        const val BAD_REQUEST = 400
        val EMPLOYEE = Caller.of(
            subject = "erin",
            attributes = mapOf(DevelopmentHeaders.ROLES_ATTRIBUTE to listOf("employee")),
        )

        /** The buyer [id]: the user, and the owner of what it creates. */
        fun buyer(id: String): Caller = Caller.of(subject = id, scope = Scope(ownerId = id))
    }
}
