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

package me.ahoo.wow.example.shop.domain.order

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.example.shop.api.ExampleContext
import me.ahoo.wow.example.shop.api.cart.AddCartItem
import me.ahoo.wow.example.shop.api.order.OrderCreated
import me.ahoo.wow.example.shop.api.order.OrderStatus
import me.ahoo.wow.example.shop.api.order.PayOrder
import me.ahoo.wow.example.shop.api.order.ShipOrder
import me.ahoo.wow.example.shop.domain.cart.CartState
import me.ahoo.wow.example.shop.domain.cart.cartModel
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.ORDER_ID
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.createOrder
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.model
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.orderCreated
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.total
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemorySnapshotStore
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.spi.log.EventRecord
import org.junit.jupiter.api.Test

/** The shop's states as snapshots, and its events as the log holds them. */
class OrderLogTest {
    private val codec = EventCodec()
    private var sequence = 0

    private suspend fun Kernel.send(model: AggregateModel<*>, id: String, body: Any): CommandOutcome {
        val ref = model.type.ref("tenant", id)
        return execute(model, Command("c-${++sequence}", "r-$sequence", ref, body, scope = Scope("ahoo")))
    }

    @Test
    fun `an order should go on from its snapshot`(): Unit = runTest {
        val log = InMemoryEventLog()
        val snapshots = InMemorySnapshotStore()
        val writer = Kernel(log, codec)
        writer.send(model, ORDER_ID, createOrder)
        val paid = writer.send(model, ORDER_ID, PayOrder("payment-1", total)) as CommandOutcome.Committed
        snapshots.save(
            codec.snapshotOf(model, paid.stream.ref, paid.state as OrderState, 2, paid.stream.scope, 0, 0),
        )

        // A kernel without the state cached restores the paid order from the snapshot, and ships it.
        val shipped = Kernel(log, codec, snapshots = snapshots).send(model, ORDER_ID, ShipOrder)
        val state = (shipped as CommandOutcome.Committed).state as OrderState
        state.status.assert().isEqualTo(OrderStatus.SHIPPED)
        state.paidAmount.assert().isEqualTo(total)
        state.items.assert().isEqualTo(orderCreated.items)
        state.address.assert().isEqualTo(orderCreated.address)
    }

    @Test
    fun `a cart's state should read back from its snapshot`(): Unit = runTest {
        val cart = cartModel(ExampleContext.NAME)
        val added = Kernel(InMemoryEventLog(), codec).send(cart, "ahoo", AddCartItem("apple", 2))
            as CommandOutcome.Committed
        val snapshot = codec.snapshotOf(cart, added.stream.ref, added.state as CartState, 1, added.stream.scope, 0, 0)
        codec.decodeState(cart, snapshot.state).items.assert().isEqualTo((added.state as CartState).items)
    }

    @Test
    fun `an OrderCreated of 9x, at revision 1, should be upgraded`() {
        val nineX = """
            {"orderId":"$ORDER_ID","items":[
              {"id":"$ORDER_ID-1","productId":"apple","price":2.50,"quantity":2,"totalPrice":5.00},
              {"id":"$ORDER_ID-2","productId":"pear","price":4.00,"quantity":1,"totalPrice":4.00}
            ],"address":{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong",
            "detail":"1 Century Avenue"},"fromCart":true}
        """.trimIndent()
        val upgraded = codec.decode(model, EventRecord("example.order.OrderCreated", 1, nineX)) as OrderCreated
        upgraded.assert().isEqualTo(orderCreated)
    }
}
