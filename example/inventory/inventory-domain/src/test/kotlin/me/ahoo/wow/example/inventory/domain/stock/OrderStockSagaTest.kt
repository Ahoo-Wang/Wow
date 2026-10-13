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

package me.ahoo.wow.example.inventory.domain.stock

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.inventory.api.CommitReservation
import me.ahoo.wow.example.inventory.api.ReleaseReservation
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.spi.state.StateEventPayload
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

/** The shop's published order stream, settling each product's reservation in the inventory. */
class OrderStockSagaTest {
    private val order = AggregateType("example", "order").ref("tenant", ORDER_ID)
    private val view = OrderView(
        listOf(OrderView.Item("apple", 2), OrderView.Item("pear", 1), OrderView.Item("apple", 1)),
    )

    private fun event(name: String, version: Int = 3) = StateEvent(
        order,
        version,
        "command-$version",
        "request-$version",
        "example.order.Command",
        1L,
        listOf(StateEventPayload(name, 1, JsonNodeFactory.instance.objectNode())),
        view,
    )

    private fun context(event: StateEvent<OrderView>, subscription: String) =
        ExecutionSagaContext(ExecutionId(subscription, event.ref, event.version), event)

    @Test
    fun `a shipped order should commit the reservation of each of its products`() {
        val shipped = event(OrderStockSaga.ORDER_SHIPPED)
        val commands = OrderStockSaga().onOrderShipped(shipped, context(shipped, SHIPPED))
        commands.map { it.ref.id }.assert().containsExactly("apple", "pear")
        commands.forEach {
            it.ref.type.assert().isEqualTo(AggregateType("inventory", "stock"))
            it.ref.tenantId.assert().isEqualTo("tenant")
            it.body.assert().isEqualTo(CommitReservation(ORDER_ID))
            it.causation!!.causationId.assert().isEqualTo("example.order/tenant/$ORDER_ID@3")
        }
        // The same record handled again derives the same requests, which the kernel decides once.
        OrderStockSaga().onOrderShipped(shipped, context(shipped, SHIPPED)).map { it.requestId }
            .assert().isEqualTo(commands.map { it.requestId })
    }

    @Test
    fun `a cancelled order should release the reservation of each of its products`() {
        val cancelled = event(OrderStockSaga.ORDER_CANCELLED)
        OrderStockSaga().onOrderCancelled(cancelled, context(cancelled, CANCELLED)).map { it.ref.id to it.body }
            .assert().containsExactly("apple" to ReleaseReservation(ORDER_ID), "pear" to ReleaseReservation(ORDER_ID))
    }

    @Test
    fun `the order's other events should derive nothing`() {
        val created = event("example.order.OrderCreated", version = 1)
        OrderStockSaga().onOrderShipped(created, context(created, SHIPPED)).assert().isEmpty()
        OrderStockSaga().onOrderCancelled(created, context(created, CANCELLED)).assert().isEmpty()
        val shipped = event(OrderStockSaga.ORDER_SHIPPED)
        OrderStockSaga().onOrderCancelled(shipped, context(shipped, CANCELLED)).assert().isEmpty()
    }

    private companion object {
        const val ORDER_ID = "order-1"
        const val SHIPPED = "OrderStockSaga.onOrderShipped"
        const val CANCELLED = "OrderStockSaga.onOrderCancelled"
    }
}
