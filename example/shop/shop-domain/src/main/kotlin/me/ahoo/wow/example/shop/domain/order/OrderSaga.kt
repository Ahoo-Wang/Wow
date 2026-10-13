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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnRejected
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.inventory.api.InventoryContext
import me.ahoo.wow.example.inventory.api.ReserveStock
import me.ahoo.wow.example.shop.api.order.CancelOrder
import me.ahoo.wow.example.shop.api.order.OrderCreated

/**
 * Reserves an order's stock in the inventory context and cancels the order when it cannot (decision D144).
 *
 * - [onOrderCreated] sends `inventory.stock.ReserveStock` to each product's stock, the quantities of its lines added
 *   up. The inventory is another context, served by another service: the commands go to its command topic,
 *   `wow.inventory.commands`, and the saga's hop is done once the broker has them (decisions D33, D137). Their ids are
 *   fixed by the order's commit, so handling it again sends the same requests, which the inventory decides once.
 * - [onReserveRejected] takes the inventory's refusal, which comes back as a receipt on `wow.example.receipts`
 *   (decision D136), and cancels the order; the inventory then releases what it reserved for the order's other
 *   products when it reads the cancellation from the order stream.
 *
 * The saga finds the inventory's commands by their classes in the inventory's Manifest, a peer of this service
 * (decision D139).
 */
@Saga
class OrderSaga {
    @OnEvent
    fun onOrderCreated(event: StateEvent<OrderState>, context: SagaContext): List<Command<ReserveStock>> {
        val created = event.events.filterIsInstance<OrderCreated>().firstOrNull() ?: return emptyList()
        val quantities = created.items.groupingBy { it.productId }.fold(0) { total, item -> total + item.quantity }
        return quantities.map { (productId, quantity) ->
            context.command(STOCK.ref(event.ref.tenantId, productId), ReserveStock(event.ref.id, quantity))
        }
    }

    /** A product the inventory cannot reserve cancels the order, which derived the reservation. */
    @OnRejected
    fun onReserveRejected(rejected: CommandRejected<ReserveStock>, context: SagaContext): Command<CancelOrder> =
        context.command(
            rejected.executionId.ref,
            CancelOrder("${rejected.ref.id} cannot be reserved: ${rejected.error.message}"),
        )

    private companion object {
        val STOCK = AggregateType(InventoryContext.NAME, InventoryContext.STOCK)
    }
}
