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

import me.ahoo.wow.example.eshop.payment.api.payment.PaymentFailed
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRefunded
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentStatus
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentSucceeded
import java.math.BigDecimal

/**
 * The state of a [Payment], changed in place by its events. Every property has a value before the payment is
 * requested: the kernel serializes the state as it is.
 */
class PaymentState {
    var orderId: String = ""
        private set

    var amount: BigDecimal = BigDecimal.ZERO
        private set

    var status: PaymentStatus = PaymentStatus.REQUESTED
        private set

    /** The gateway's transaction of a succeeded payment; null until then. */
    var transactionId: String? = null
        private set

    /** Why the gateway declined the payment, or why it was refunded; null otherwise. */
    var reason: String? = null
        private set

    fun onSourcing(event: PaymentRequested) {
        orderId = event.orderId
        amount = event.amount
        status = PaymentStatus.REQUESTED
    }

    fun onSourcing(event: PaymentSucceeded) {
        transactionId = event.transactionId
        status = PaymentStatus.SUCCEEDED
    }

    fun onSourcing(event: PaymentFailed) {
        reason = event.reason
        status = PaymentStatus.FAILED
    }

    fun onSourcing(event: PaymentRefunded) {
        reason = event.reason
        status = PaymentStatus.REFUNDED
    }
}
