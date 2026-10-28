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

import me.ahoo.wow.api.annotation.OnCommitted
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnRejected
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.example.eshop.inventory.api.stock.CommitReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReleaseReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReserveStock
import me.ahoo.wow.example.eshop.ordering.api.order.AwaitStockValidation
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.ConfirmOrderStock
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
 * - [onReserveCommitted]: a reservation the inventory commits comes back as a receipt on `wow.eshop-ordering.receipts`
 *   and confirms the order's product: the receipt names the stock, whose id is the product's.
 * - [onReserveRejected]: a reservation the inventory rejects (a short stock, a product it has no stock of) comes back
 *   as a receipt on the same topic and cancels the order.
 * - [onOrderCancelled]: a cancelled order releases what the inventory reserved for it; a product reserved for nothing
 *   releases nothing.
 * - [onOrderShipped]: a shipped order commits its reservations.
 *
 * [onReserveCommitted] is a hop of a wait chain: a checkout's `Wow-Wait` follows the reservations to their commit in
 * the inventory, then their receipts back to it, and on into the confirmations it derives.
 *
 * Each handler names only its target's id: the contract the inventory api's jar carries assigns
 * the reservations to the inventory's stock (whose id is the product's), the ordering's own Manifest assigns
 * the order's commands to the order, and each command is in the tenant of the commit or receipt it answers.
 */
@Saga
class OrderProcessSaga {
    // region derive
    @OnEvent
    fun onOrderSubmitted(
        event: StateTypedEvent<OrderState, OrderSubmitted>
    ): CommandRequest<AwaitStockValidation> = CommandRequest.of(AwaitStockValidation, event.ref.id)
    // endregion

    @OnEvent
    fun onOrderAwaitingValidation(
        event: StateTypedEvent<OrderState, OrderAwaitingValidation>
    ): List<CommandRequest<ReserveStock>> = event.state.quantities().map { (productId, quantity) ->
        CommandRequest.of(ReserveStock(event.ref.id, quantity), productId)
    }

    // region committed
    /** A product the inventory reserved confirms it on the order that asked for it. */
    @OnCommitted
    fun onReserveCommitted(
        committed: CommandCommitted<ReserveStock>
    ): CommandRequest<ConfirmOrderStock> =
        CommandRequest.of(ConfirmOrderStock(committed.ref.id), committed.source.id)
    // endregion

    // region rejected
    /** A product the inventory cannot reserve cancels the order that asked for it. */
    @OnRejected
    fun onReserveRejected(rejected: CommandRejected<ReserveStock>): CommandRequest<CancelOrder> =
        CommandRequest.of(
            CancelOrder("${rejected.ref.id} cannot be reserved: ${rejected.error.message}"),
            rejected.source.id,
        )
    // endregion

    @OnEvent
    fun onOrderCancelled(
        event: StateTypedEvent<OrderState, OrderCancelled>
    ): List<CommandRequest<ReleaseReservation>> = event.state.products().map { productId ->
        CommandRequest.of(ReleaseReservation(event.ref.id), productId)
    }

    // region release-rejected
    /**
     * A release the inventory rejects needs no compensation: it rejects one only for a product it has no stock of,
     * which reserved nothing either. Without this handler, the rejection would park the order for the saga; returning
     * `Unit` accepts it and derives nothing.
     */
    @Suppress("UnusedParameter")
    @OnRejected
    fun onReleaseRejected(rejected: CommandRejected<ReleaseReservation>) = Unit
    // endregion

    @OnEvent
    fun onOrderShipped(
        event: StateTypedEvent<OrderState, OrderShipped>
    ): List<CommandRequest<CommitReservation>> = event.state.products().map { productId ->
        CommandRequest.of(CommitReservation(event.ref.id), productId)
    }
}
