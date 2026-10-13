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

package me.ahoo.wow.example.eshop.payment.domain.payment

import java.math.BigDecimal

/**
 * The payment gateway the payment context charges: an outside system, a domain service of [PaymentGatewaySaga]. The
 * server binds one; the eShop has only the [SimulatedPaymentGateway].
 */
fun interface PaymentGateway {
    /**
     * Charges [amount] for the payment [paymentId]. A gateway charges once per [idempotencyKey]: the saga's execution,
     * the same each time it handles the same commit, so a retried charge is not charged twice.
     */
    suspend fun charge(paymentId: String, amount: BigDecimal, idempotencyKey: String): GatewayOutcome
}

/** What the gateway answers a charge. */
sealed interface GatewayOutcome {
    /** The gateway charged the amount, under its [transactionId]. */
    data class Approved(val transactionId: String) : GatewayOutcome

    /** The gateway refused the charge, for [reason]. */
    data class Declined(val reason: String) : GatewayOutcome
}

/**
 * A gateway for trying the eShop out and for its tests, after the classic eShop's `PaymentProcessor`, which approves
 * or declines by a setting. Its answer is a function of the amount, so a test chooses the outcome by the order's total:
 * it approves an amount up to [limit] and declines one above it. Its transaction id is the charge's idempotency key.
 */
class SimulatedPaymentGateway(val limit: BigDecimal) : PaymentGateway {
    init {
        require(limit.signum() >= 0) { "A gateway's limit is not negative, not $limit." }
    }

    override suspend fun charge(paymentId: String, amount: BigDecimal, idempotencyKey: String): GatewayOutcome =
        if (amount <= limit) {
            GatewayOutcome.Approved("simulated-$idempotencyKey")
        } else {
            GatewayOutcome.Declined("The gateway declined $amount for $paymentId: it is over the limit of $limit.")
        }
}
