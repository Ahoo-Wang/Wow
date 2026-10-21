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

package me.ahoo.wow.example.eshop.ordering.api.order

import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary
import java.math.BigDecimal

/*
 * An order's id comes from the route (`/eshop-ordering/order/{id}/...`) or, for a command a saga sends, from the
 * aggregate reference it is sent to: no command carries an identity field.
 */

// region submit-order
/**
 * Submits an order of [items]. The basket context sends it when its buyer checks out, with the order's id fixed by the
 * basket. The order belongs to the buyer: a saga's command carries the scope of the basket it is derived from, whose
 * owner is its buyer (decision D189), and the order takes its owner from the command that creates it.
 */
@CreateAggregate
@Summary("Submit an order")
data class SubmitOrder(
    val items: List<OrderItem>,
)
// endregion

/** Moves a submitted order on to the stock validation; the order-process saga sends it. */
@Summary("Await the stock validation")
data object AwaitStockValidation

/**
 * Confirms that the inventory reserved the order's units of [productId]. The stock-confirmation saga sends it for
 * each reservation it reads from the inventory's stock stream; once every product is confirmed, the order awaits its
 * payment.
 */
@Summary("Confirm a product's stock")
data class ConfirmOrderStock(
    val productId: String,
)

/**
 * Pays [amount] of the order with the payment [paymentId]. The payment context sends it once its gateway approves the
 * order's payment; a client may send it too.
 */
@Summary("Pay the order")
data class PayOrder(
    val paymentId: String,
    val amount: BigDecimal,
)

/** Ships a paid order. */
@Summary("Ship the order")
data object ShipOrder

/**
 * Cancels an order that is not paid yet, for [reason]. The order-process saga sends it when the inventory cannot
 * reserve a product, the payment context when its gateway declines the order's payment, and the order schedules it for
 * itself when it is not paid within the payment grace period.
 */
@Summary("Cancel the order")
data class CancelOrder(
    val reason: String,
)
