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

package me.ahoo.wow.example.eshop.ordering.domain.order

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.eshop.ordering.api.order.ConfirmOrderStock
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.spi.state.StateEventPayload
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** The stock-confirmation saga on the inventory's published stock stream. */
class StockConfirmationSagaTest {
    private val stock = AggregateType("eshop-inventory", "stock").ref(TENANT, "mug")
    private val json = JsonMapper.builder().build()

    private fun payload(name: String, orderId: String) =
        StateEventPayload(name, 1, json.createObjectNode().put("orderId", orderId).put("quantity", 2))

    private fun event(vararg events: Any) = StateEvent(
        stock,
        3,
        "command-3",
        "request-3",
        "eshop-inventory.stock.ReserveStock",
        1L,
        events.toList(),
        StockView(available = 8),
    )

    private fun context(event: StateEvent<StockView>) =
        ExecutionSagaContext(ExecutionId("StockConfirmationSaga.onStockReserved", event.ref, event.version), event)

    @Test
    fun `a reservation should confirm the order's product`() {
        val event = event(payload(StockConfirmationSaga.STOCK_RESERVED, "alice-1"))
        val commands = StockConfirmationSaga().onStockReserved(event, context(event))
        commands.map { it.ref to it.body }.assert().containsExactly(
            AggregateType("eshop-ordering", "order").ref(TENANT, "alice-1") to ConfirmOrderStock("mug"),
        )
        // The same record read again derives the same request.
        StockConfirmationSaga().onStockReserved(event, context(event)).map { it.requestId }
            .assert().isEqualTo(commands.map { it.requestId })
    }

    @Test
    fun `another event of the stock should confirm nothing`() {
        val event = event(payload("eshop-inventory.stock.ReservationReleased", "alice-1"))
        StockConfirmationSaga().onStockReserved(event, context(event)).assert().isEmpty()
    }

    private companion object {
        const val TENANT = "tenant"
    }
}
