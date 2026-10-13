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

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.example.inventory.api.CommitReservation
import me.ahoo.wow.example.inventory.api.ReceiveStock
import me.ahoo.wow.example.inventory.api.ReleaseReservation
import me.ahoo.wow.example.inventory.api.ReservationCommitted
import me.ahoo.wow.example.inventory.api.ReservationReleased
import me.ahoo.wow.example.inventory.api.ReserveStock
import me.ahoo.wow.example.inventory.api.StockReceived
import me.ahoo.wow.example.inventory.api.StockReserved

/**
 * A product's stock, its id the product's id (decision D144). It takes over what the shop's `InventoryService`
 * answered: the shop no longer asks how many units are in stock, its order saga reserves them here, and a reservation
 * this stock rejects comes back to the saga as a receipt (decision D136).
 *
 * Every command for one order is idempotent by the order's id, besides the kernel's de-duplication by request id: a
 * second reservation for an order already reserved, and releasing one that is not, change nothing.
 */
@AggregateRoot
@Description("A product's stock: units on hand, and those reserved for orders")
class Stock(private val state: StockState) {
    @OnCommand(produces = [StockReceived::class])
    fun onCommand(command: ReceiveStock): Decision = whenPositive(command.quantity) {
        Decision.events(StockReceived(command.quantity))
    }

    @OnCommand(produces = [StockReserved::class])
    fun onCommand(command: ReserveStock): Decision = whenPositive(command.quantity) {
        when {
            command.orderId in state.reservations -> Decision.NoOp
            command.quantity > state.available -> Decision.reject(
                StockRejections.INSUFFICIENT,
                "Only ${state.available} of ${state.id} are available, not ${command.quantity}.",
            )

            else -> Decision.events(StockReserved(command.orderId, command.quantity))
        }
    }

    /**
     * Commits the order's reservation once it is shipped. Nothing reserved for the order is a rejection: the saga that
     * sends it has no handler for it, so its aggregate parks (decision D153) until someone looks.
     */
    @OnCommand(produces = [ReservationCommitted::class])
    fun onCommand(command: CommitReservation): Decision {
        val quantity = state.reservations[command.orderId] ?: return Decision.reject(
            StockRejections.NO_RESERVATION,
            "Nothing of ${state.id} is reserved for the order ${command.orderId}.",
        )
        return Decision.events(ReservationCommitted(command.orderId, quantity))
    }

    /** Releasing a reservation the order does not hold changes nothing: the line may have been rejected. */
    @OnCommand(produces = [ReservationReleased::class])
    fun onCommand(command: ReleaseReservation): Decision {
        val quantity = state.reservations[command.orderId] ?: return Decision.NoOp
        return Decision.events(ReservationReleased(command.orderId, quantity))
    }

    private inline fun whenPositive(quantity: Int, decide: () -> Decision): Decision =
        if (quantity > 0) {
            decide()
        } else {
            Decision.reject(StockRejections.INVALID_QUANTITY, "$quantity is not a positive quantity.")
        }
}
