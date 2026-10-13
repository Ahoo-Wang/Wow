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

package me.ahoo.wow.example.inventory.api

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.Summary

/*
 * A stock's id is its product's id, taken from the route (`/inventory/stock/{id}/...`) or, for a command another
 * context sends, from the aggregate reference it is sent to: no command carries an identity field (decision D68).
 */

/** Takes [quantity] units of the product into stock, creating its stock on the first delivery. */
@AllowCreate
@Summary("Receive stock")
data class ReceiveStock(
    @field:Positive
    val quantity: Int,
)

/**
 * Holds [quantity] units for the order [orderId] until the order is shipped or cancelled. The shop's order saga sends
 * it for each product of a new order; it is rejected when fewer units are available.
 */
@Summary("Reserve stock for an order")
data class ReserveStock(
    @field:NotBlank
    val orderId: String,
    @field:Positive
    val quantity: Int,
)

/** The order [orderId] was shipped: the units reserved for it leave the stock. */
@Summary("Commit an order's reservation")
data class CommitReservation(
    @field:NotBlank
    val orderId: String,
)

/** The order [orderId] will not be shipped: the units reserved for it are available again. */
@Summary("Release an order's reservation")
data class ReleaseReservation(
    @field:NotBlank
    val orderId: String,
)
