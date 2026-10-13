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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.inventory.api.CommitReservation
import me.ahoo.wow.example.inventory.api.InventoryContext
import me.ahoo.wow.example.inventory.api.ReleaseReservation
import me.ahoo.wow.spi.state.StateEventPayload

/**
 * The inventory's side of an order's life (decision D144): it reads the order stream the shop publishes,
 * `wow.example.order.state` (decisions D134, D138), and settles each product's reservation in this context.
 *
 * - [onOrderShipped]: a shipped order commits the reservation of each of its products.
 * - [onOrderCancelled]: a cancelled order releases them.
 *
 * Each handler is its own subscription over the topic (`OrderStockSaga.onOrderShipped`, ...), with offsets kept in
 * this service's checkpoints. The commands it derives are local ones, with ids fixed by the record it handles, so a
 * record read twice (after a restart, or a copy on the topic) derives the same requests and the kernel decides them
 * once.
 *
 * The events of another context's stream are its published payloads ([StateEventPayload]: a name, a revision and the
 * JSON), not the shop's classes, and the state is this context's own view of the order, [OrderView], decoded from the
 * published state. The shop publishes the order with its state, so the view is not nullable (decision D140): the saga
 * needs the order's items, which no event of shipping or cancelling carries.
 */
@Saga
class OrderStockSaga {
    @OnEvent(aggregates = [ORDER])
    fun onOrderShipped(event: StateEvent<OrderView>, context: SagaContext): List<Command<CommitReservation>> =
        if (event.has(ORDER_SHIPPED)) {
            event.state.products().map { productId ->
                context.command(STOCK.ref(event.ref.tenantId, productId), CommitReservation(event.ref.id))
            }
        } else {
            emptyList()
        }

    @OnEvent(aggregates = [ORDER])
    fun onOrderCancelled(event: StateEvent<OrderView>, context: SagaContext): List<Command<ReleaseReservation>> =
        if (event.has(ORDER_CANCELLED)) {
            event.state.products().map { productId ->
                context.command(STOCK.ref(event.ref.tenantId, productId), ReleaseReservation(event.ref.id))
            }
        } else {
            emptyList()
        }

    private fun StateEvent<*>.has(name: String): Boolean =
        events.any { it is StateEventPayload && it.name == name }

    companion object {
        /** The shop's order, which publishes its stream (decision D156). */
        const val ORDER = "example.order"
        const val ORDER_SHIPPED = "example.order.OrderShipped"
        const val ORDER_CANCELLED = "example.order.OrderCancelled"
        private val STOCK = AggregateType(InventoryContext.NAME, InventoryContext.STOCK)
    }
}

/**
 * The inventory's view of the shop's order: the products of its [items], read from the state the shop publishes. The
 * order's other properties are left out; reading ignores them.
 */
data class OrderView(val items: List<Item> = emptyList()) {
    data class Item(val productId: String, val quantity: Int)

    /** The order's products, each once, in the order of their first line. */
    fun products(): Set<String> = items.mapTo(linkedSetOf()) { it.productId }
}
