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
import me.ahoo.wow.api.annotation.OnRejected
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.example.eshop.inventory.api.InventoryContext
import me.ahoo.wow.example.eshop.inventory.api.stock.CommitReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReleaseReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReserveStock
import me.ahoo.wow.example.eshop.ordering.api.order.AwaitStockValidation
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.OrderAwaitingValidation
import me.ahoo.wow.example.eshop.ordering.api.order.OrderCancelled
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted

/**
 * The order process, after the classic eShop's `OrderProcessor`: it drives an order through the stock validation in
 * the inventory context, and compensates when the inventory refuses.
 *
 * - [onOrderSubmitted]: a submitted order moves on to await its stock validation.
 * - [onOrderAwaitingValidation]: the order reserves each product's stock in the inventory, its lines of one product
 *   added up. The inventory is another context: the commands go to its command topic, `wow.eshop-inventory.commands`.
 * - [onReserveRejected]: a reservation the inventory rejects (a short stock, a product it has no stock of) comes back
 *   as a receipt on `wow.eshop-ordering.receipts` and cancels the order.
 * - [onOrderCancelled]: a cancelled order releases what the inventory reserved for it; a product reserved for nothing
 *   releases nothing.
 * - [onOrderShipped]: a shipped order commits its reservations.
 *
 * The confirmations of the reservations come back on another path: [StockConfirmationSaga] reads them from the
 * inventory's stock stream.
 */
@Saga
class OrderProcessSaga {
    @OnEvent
    fun onOrderSubmitted(event: StateEvent<OrderState>, context: SagaContext): Command<AwaitStockValidation>? =
        if (event.events.any { it is OrderSubmitted }) context.command(event.ref, AwaitStockValidation) else null

    @OnEvent
    fun onOrderAwaitingValidation(event: StateEvent<OrderState>, context: SagaContext): List<Command<ReserveStock>> =
        if (event.events.any { it is OrderAwaitingValidation }) {
            event.state.quantities().map { (productId, quantity) ->
                context.command(STOCK.ref(event.ref.tenantId, productId), ReserveStock(event.ref.id, quantity))
            }
        } else {
            emptyList()
        }

    /** A product the inventory cannot reserve cancels the order that asked for it. */
    @OnRejected
    fun onReserveRejected(rejected: CommandRejected<ReserveStock>, context: SagaContext): Command<CancelOrder> =
        context.command(
            rejected.executionId.ref,
            CancelOrder("${rejected.ref.id} cannot be reserved: ${rejected.error.message}"),
        )

    @OnEvent
    fun onOrderCancelled(event: StateEvent<OrderState>, context: SagaContext): List<Command<ReleaseReservation>> =
        if (event.events.any { it is OrderCancelled }) {
            event.state.products().map { productId ->
                context.command(STOCK.ref(event.ref.tenantId, productId), ReleaseReservation(event.ref.id))
            }
        } else {
            emptyList()
        }

    /**
     * A release the inventory rejects needs no compensation: it rejects one only for a product it has no stock of,
     * which reserved nothing either. Without this handler, the rejection would park the order for the saga.
     */
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    @OnRejected
    fun onReleaseRejected(rejected: CommandRejected<ReleaseReservation>): Command<CancelOrder>? = null

    @OnEvent
    fun onOrderShipped(event: StateEvent<OrderState>, context: SagaContext): List<Command<CommitReservation>> =
        if (event.events.any { it is OrderShipped }) {
            event.state.products().map { productId ->
                context.command(STOCK.ref(event.ref.tenantId, productId), CommitReservation(event.ref.id))
            }
        } else {
            emptyList()
        }

    private companion object {
        val STOCK = AggregateType(InventoryContext.NAME, InventoryContext.STOCK)
    }
}
