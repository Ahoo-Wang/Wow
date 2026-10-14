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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.eshop.ordering.api.OrderingContext
import me.ahoo.wow.example.eshop.ordering.api.order.ConfirmOrderStock
import me.ahoo.wow.spi.state.StateEventPayload

/**
 * Confirms an order's products as the inventory reserves them, read from the stock stream the inventory context
 * publishes, `wow.eshop-inventory.stock.state`. A rejected reservation comes back as a receipt; an accepted one has
 * no receipt, so the order learns of it from the stream.
 *
 * The events of another context's stream are its published payloads ([StateEventPayload]: a name, a revision and the
 * JSON), matched by name; the order's id is read from the JSON of `StockReserved`. The commands it derives are this
 * context's own, with ids fixed by the record it handles, so a record read twice derives the same requests.
 *
 * The handler reads another context's stream, so it starts at the beginning of it by default (decision D194): a
 * reservation published before the ordering service first read the topic is not missed.
 */
@Saga
class StockConfirmationSaga {
    @OnEvent(aggregates = [STOCK])
    fun onStockReserved(event: StateEvent<StockView>, context: SagaContext): List<Command<ConfirmOrderStock>> =
        event.events.filterIsInstance<StateEventPayload>()
            .filter { it.name == STOCK_RESERVED }
            .map { reserved ->
                val orderId = reserved.payload.get("orderId").asString()
                context.command(ORDER.ref(event.ref.tenantId, orderId), ConfirmOrderStock(event.ref.id))
            }

    companion object {
        /** The inventory's stock, which publishes its stream. */
        const val STOCK = "eshop-inventory.stock"
        const val STOCK_RESERVED = "eshop-inventory.stock.StockReserved"
        private val ORDER = AggregateType(OrderingContext.NAME, OrderingContext.ORDER)
    }
}

/**
 * The ordering context's view of the inventory's stock, decoded from the state the inventory publishes. The saga
 * needs nothing of it but the events; the view keeps what is available, and reading ignores the rest.
 */
data class StockView(val available: Int = 0)
