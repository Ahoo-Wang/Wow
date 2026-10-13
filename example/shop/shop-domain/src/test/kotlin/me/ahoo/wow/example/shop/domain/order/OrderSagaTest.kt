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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.inventory.api.ReserveStock
import me.ahoo.wow.example.shop.api.order.CancelOrder
import me.ahoo.wow.example.shop.api.order.OrderItem
import me.ahoo.wow.example.shop.api.order.OrderPaid
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.rejectionContextOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The order saga: a placed order reserves its stock in the inventory, and a refusal cancels the order. */
class OrderSagaTest {
    private val order = AggregateType("example", "order").ref("tenant", OrderFixtures.ORDER_ID)
    private val stock = AggregateType("inventory", "stock")

    private fun event(events: List<Any> = listOf(OrderFixtures.orderCreated)) = StateEvent(
        order,
        1,
        "command-1",
        "request-1",
        "example.order.CreateOrder",
        1L,
        events,
        OrderState(OrderFixtures.ORDER_ID),
    )

    private fun context(event: StateEvent<OrderState>) =
        ExecutionSagaContext(ExecutionId(SUBSCRIPTION, event.ref, event.version), event)

    @Test
    fun `a placed order should reserve each product's stock in the inventory`() {
        val event = event()
        val commands = OrderSaga().onOrderCreated(event, context(event))
        commands.map { it.ref to it.body }.assert().containsExactly(
            stock.ref("tenant", "apple") to ReserveStock(OrderFixtures.ORDER_ID, 2),
            stock.ref("tenant", "pear") to ReserveStock(OrderFixtures.ORDER_ID, 1),
        )
        commands.first().causation!!.causationId.assert().isEqualTo("example.order/tenant/order-1@1")
        // Handling the commit again derives the same requests, which the inventory decides once.
        OrderSaga().onOrderCreated(event, context(event)).map { it.requestId }
            .assert().isEqualTo(commands.map { it.requestId })
    }

    @Test
    fun `lines of one product should reserve their quantities together`() {
        val created = OrderFixtures.orderCreated.copy(
            items = listOf(
                OrderItem("order-1-1", "apple", OrderFixtures.APPLE_PRICE, 2),
                OrderItem("order-1-2", "apple", OrderFixtures.APPLE_PRICE, 3),
            ),
        )
        val event = event(listOf(created))
        OrderSaga().onOrderCreated(event, context(event)).single().body
            .assert().isEqualTo(ReserveStock(OrderFixtures.ORDER_ID, 5))
    }

    @Test
    fun `another event of the order should reserve nothing`() {
        val event = event(listOf(OrderPaid(BigDecimal.ONE, paid = false)))
        OrderSaga().onOrderCreated(event, context(event)).assert().isEmpty()
    }

    @Test
    fun `a reservation the inventory refused should cancel the order`() = runTest {
        val handler = ModelProvider.load().single().processors(Dependencies.NONE).single { it.name == SUBSCRIPTION }
        val onRejected = handler.rejectionHandlerOf(ReserveStock::class.java)!!
        onRejected.name.assert().isEqualTo("OrderSaga.onReserveRejected")
        val cancel = onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected)).single()
        cancel.ref.assert().isEqualTo(order)
        cancel.body.assert().isEqualTo(CancelOrder("pear cannot be reserved: Only 0 of pear are available, not 1."))
        // The same receipt again derives the same request.
        onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected)).single().requestId
            .assert().isEqualTo(cancel.requestId)
    }

    private val rejected = CommandRejected<ReserveStock>(
        commandId = "derived-2",
        commandType = "inventory.stock.ReserveStock",
        ref = stock.ref("tenant", "pear"),
        error = ErrorInfo("inventory.stock.insufficient", "Only 0 of pear are available, not 1."),
        causation = Causation("command-1", "example.order/tenant/order-1@1"),
        executionId = ExecutionId(SUBSCRIPTION, order, 1),
    )

    private companion object {
        const val SUBSCRIPTION = "OrderSaga.onOrderCreated"
    }
}
