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

package me.ahoo.wow.example.eshop.payment.api.payment

import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary
import java.math.BigDecimal

/*
 * A payment's id is its order's: one payment per order, so the commands a saga derives for an order name the same
 * payment however often they are derived. No command carries an identity field.
 */

/**
 * Requests the payment of [amount] for the order [orderId]. The order-payment saga sends it once the ordering context
 * confirms the order's stock.
 */
@CreateAggregate
@Summary("Request a payment")
data class RequestPayment(
    val orderId: String,
    val amount: BigDecimal,
)

/** Records that the gateway approved the payment, under its [transactionId]. The gateway saga sends it. */
@Summary("Confirm a payment")
data class ConfirmPayment(
    val transactionId: String,
)

/** Records that the gateway declined the payment, for [reason]. The gateway saga sends it. */
@Summary("Fail a payment")
data class FailPayment(
    val reason: String,
)

/**
 * Refunds a confirmed payment, for [reason]. The order-payment saga sends it when the ordering context refuses the
 * payment: the order was cancelled meanwhile, when its grace period passed, say.
 */
@Summary("Refund a payment")
data class RefundPayment(
    val reason: String,
)
