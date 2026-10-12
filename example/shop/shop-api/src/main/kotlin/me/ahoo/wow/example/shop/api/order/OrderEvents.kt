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

package me.ahoo.wow.example.shop.api.order

import me.ahoo.wow.api.annotation.Revision
import me.ahoo.wow.api.annotation.Summary
import java.math.BigDecimal

/**
 * The order was placed.
 *
 * Revision 2: the 9.x event (revision 1) also carried the order's id, which V10 keeps in the stream's aggregate
 * reference only (decision D68), and each item's computed total price. The domain's `@Upgrade` drops both, so a
 * migrated 9.x log still reads.
 */
@Revision(2)
@Summary("The order was placed")
data class OrderCreated(
    val items: List<OrderItem>,
    val address: ShippingAddress,
    val fromCart: Boolean,
) {
    /** What the order costs: the sum of its items' totals. */
    fun totalAmount(): BigDecimal = items.fold(BigDecimal.ZERO) { total, item -> total + item.totalPrice() }
}

@Summary("The shipping address changed")
data class AddressChanged(
    val shippingAddress: ShippingAddress,
)

/** What paying an order produces; a sealed type, so the Manifest knows every event `PayOrder` can produce. */
sealed interface OrderPayment

/** [amount] was paid; [paid] tells whether the order is now paid in full. */
@Summary("A payment was received")
data class OrderPaid(val amount: BigDecimal, val paid: Boolean) : OrderPayment

/** The payment [paymentId] exceeded what was payable by [overPay], which the payment service refunds. */
@Summary("A payment exceeded the amount payable")
data class OrderOverPaid(val paymentId: String, val overPay: BigDecimal) : OrderPayment

/** The payment [paymentId] arrived for an order that is no longer payable, so the payment service refunds it. */
@Summary("A payment arrived for an order that is not payable")
data class OrderPayDuplicated(val paymentId: String, val reason: String) : OrderPayment

@Summary("The order was shipped")
data object OrderShipped

@Summary("The order was received")
data object OrderReceived
