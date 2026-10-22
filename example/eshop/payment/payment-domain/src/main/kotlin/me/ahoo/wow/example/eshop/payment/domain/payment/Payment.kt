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

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.example.eshop.payment.api.payment.ConfirmPayment
import me.ahoo.wow.example.eshop.payment.api.payment.FailPayment
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentFailed
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRefunded
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentStatus
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentSucceeded
import me.ahoo.wow.example.eshop.payment.api.payment.RefundPayment
import me.ahoo.wow.example.eshop.payment.api.payment.RequestPayment

/**
 * The payment of an order, after the classic eShop's `PaymentProcessor`; its id is the order's:
 *
 * ```text
 * REQUESTED -> SUCCEEDED -> REFUNDED
 *          \-> FAILED
 * ```
 *
 * - [RequestPayment] creates it: [OrderPaymentSaga] sends it once the ordering context confirms the order's stock.
 * - [PaymentGatewaySaga] charges the gateway for a requested payment and records the outcome: [ConfirmPayment] or
 *   [FailPayment]. [OrderPaymentSaga] then pays the order, or cancels it.
 * - [RefundPayment] refunds a confirmed payment the order refused.
 *
 * Recording an outcome the payment already has changes nothing, so a command sent again is harmless; recording the
 * other outcome is a rejection.
 */
@AggregateRoot
@Description("The payment of an order through the payment gateway")
class Payment(private val state: PaymentState) {
    @OnCommand(produces = [PaymentRequested::class])
    fun onCommand(command: RequestPayment): Decision =
        Decision.events(PaymentRequested(command.orderId, command.amount))

    @OnCommand(produces = [PaymentSucceeded::class])
    fun onCommand(command: ConfirmPayment): Decision = when (state.status) {
        PaymentStatus.REQUESTED -> Decision.events(PaymentSucceeded(command.transactionId))
        PaymentStatus.SUCCEEDED -> Decision.NoOp
        else -> wrongStatus("be confirmed")
    }

    @OnCommand(produces = [PaymentFailed::class])
    fun onCommand(command: FailPayment): Decision = when (state.status) {
        PaymentStatus.REQUESTED -> Decision.events(PaymentFailed(command.reason))
        PaymentStatus.FAILED -> Decision.NoOp
        else -> wrongStatus("fail")
    }

    @OnCommand(produces = [PaymentRefunded::class])
    fun onCommand(command: RefundPayment): Decision = when (state.status) {
        PaymentStatus.SUCCEEDED -> Decision.events(PaymentRefunded(command.reason))
        PaymentStatus.REFUNDED -> Decision.NoOp
        else -> wrongStatus("be refunded")
    }

    private fun wrongStatus(action: String): Decision = Decision.reject(
        Rejections.WRONG_STATUS,
        "The payment is ${state.status}: it cannot $action.",
    )

    /**
     * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
     * `<context>.payment.<code>` (decision D259), as its peers read them from the api's generated contract.
     */
    object Rejections {
        @ErrorCode(description = "The payment's status does not allow the command.", status = 409)
        const val WRONG_STATUS = "wrong-status"
    }
}
