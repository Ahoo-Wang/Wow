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

package me.ahoo.wow.example.eshop.inventory.domain.stock

import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.reject
import me.ahoo.wow.example.eshop.inventory.api.stock.CommitReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReceiveStock
import me.ahoo.wow.example.eshop.inventory.api.stock.ReleaseReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReservationCommitted
import me.ahoo.wow.example.eshop.inventory.api.stock.ReservationReleased
import me.ahoo.wow.example.eshop.inventory.api.stock.ReserveStock
import me.ahoo.wow.example.eshop.inventory.api.stock.StockReceived
import me.ahoo.wow.example.eshop.inventory.api.stock.StockReserved

/**
 * A product's stock; its id is the product's id. The ordering context reserves units here for each order awaiting
 * validation, commits the reservation once the order is shipped and releases it once the order is cancelled. Those
 * commands come from another context, through this context's command topic.
 *
 * A short stock is a rejection ([Rejections.INSUFFICIENT]): nothing is written, and the rejection goes back to
 * the ordering saga that sent the reservation, as a receipt. Every command for one order is idempotent by the order's
 * id, besides the kernel's de-duplication by request id: reserving again for an order already reserved, and releasing
 * a reservation that is not there, change nothing.
 *
 * A product's stock belongs to its tenant (`Ownership.TENANT`): the order-process saga reserves it in
 * the scope of the buyer whose order it handles, which the stock does not check.
 */
@AggregateRoot(ownership = Ownership.TENANT)
@Description("A product's stock: units on hand, and those reserved for orders")
class Stock(private val state: StockState) {
    fun onCommand(command: ReceiveStock): StockReceived = StockReceived(command.quantity)

    // region reserve
    fun onCommand(command: ReserveStock, scope: DecideScope): StockReserved? = when {
        command.orderId in state.reservations -> null
        command.quantity > state.available -> reject(
            Rejections.INSUFFICIENT,
            "Only ${state.available} of ${scope.command.ref.id} are available, not ${command.quantity}.",
        )

        else -> StockReserved(command.orderId, command.quantity)
    }
    // endregion

    /** Commits the order's reservation. Nothing reserved for the order is a rejection: a shipped order was reserved. */
    fun onCommand(command: CommitReservation, scope: DecideScope): ReservationCommitted {
        val quantity = state.reservations[command.orderId] ?: reject(
            Rejections.NO_RESERVATION,
            "Nothing of ${scope.command.ref.id} is reserved for the order ${command.orderId}.",
        )
        return ReservationCommitted(command.orderId, quantity)
    }

    /** Releasing a reservation the order does not hold changes nothing: that product may be the one that was short. */
    fun onCommand(command: ReleaseReservation): ReservationReleased? {
        val quantity = state.reservations[command.orderId] ?: return null
        return ReservationReleased(command.orderId, quantity)
    }

    /**
     * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
     * `<context>.stock.<code>`, as its peers read them from the api's generated contract.
     */
    object Rejections {
        @ErrorCode(description = "Fewer units are available than the order asks for.", status = 409)
        const val INSUFFICIENT = "insufficient"

        @ErrorCode(description = "Nothing is reserved for the order.", status = 409)
        const val NO_RESERVATION = "no-reservation"
    }
}
