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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.payment.api.PaymentContext
import me.ahoo.wow.example.eshop.payment.api.payment.ConfirmPayment
import me.ahoo.wow.example.eshop.payment.api.payment.FailPayment
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentFailed
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRefunded
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentStatus
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentSucceeded
import me.ahoo.wow.example.eshop.payment.api.payment.RefundPayment
import me.ahoo.wow.example.eshop.payment.api.payment.RequestPayment
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The payment's state machine on the kernel, through the model the compiler generated. */
class PaymentSpec {
    private fun paymentSpec(block: AggregateSpec<PaymentState>.() -> Unit) =
        aggregateSpec(paymentModel(PaymentContext.NAME), id = ORDER, block = block)

    @Test
    fun `requesting a payment should create it`() {
        paymentSpec {
            whenCommand(RequestPayment(ORDER, AMOUNT)) {
                expectEvents(PaymentRequested(ORDER, AMOUNT))
                expectState {
                    orderId.assert().isEqualTo(ORDER)
                    amount.assert().isEqualByComparingTo(AMOUNT)
                    status.assert().isEqualTo(PaymentStatus.REQUESTED)
                }
            }
        }
    }

    @Test
    fun `a payment of nothing should be rejected`() {
        paymentSpec {
            whenCommand(RequestPayment(ORDER, BigDecimal.ZERO)) { expectRejected(PaymentRejections.INVALID_AMOUNT) }
        }
    }

    @Test
    fun `a requested payment should be confirmed once`() {
        paymentSpec {
            given(PaymentRequested(ORDER, AMOUNT))
            whenCommand(ConfirmPayment(TRANSACTION)) {
                expectEvents(PaymentSucceeded(TRANSACTION))
                expectState {
                    status.assert().isEqualTo(PaymentStatus.SUCCEEDED)
                    transactionId.assert().isEqualTo(TRANSACTION)
                }
            }
            whenCommand(ConfirmPayment(TRANSACTION)) { expectUnchanged() }
            whenCommand(FailPayment("too late")) { expectRejected(PaymentRejections.WRONG_STATUS) }
        }
    }

    @Test
    fun `a requested payment should fail once`() {
        paymentSpec {
            given(PaymentRequested(ORDER, AMOUNT))
            whenCommand(FailPayment(DECLINED)) {
                expectEvents(PaymentFailed(DECLINED))
                expectState {
                    status.assert().isEqualTo(PaymentStatus.FAILED)
                    reason.assert().isEqualTo(DECLINED)
                }
            }
            whenCommand(FailPayment(DECLINED)) { expectUnchanged() }
            whenCommand(ConfirmPayment(TRANSACTION)) { expectRejected(PaymentRejections.WRONG_STATUS) }
            whenCommand(RefundPayment("no")) { expectRejected(PaymentRejections.WRONG_STATUS) }
        }
    }

    @Test
    fun `a confirmed payment should be refunded once`() {
        paymentSpec {
            given(PaymentRequested(ORDER, AMOUNT))
            given(PaymentSucceeded(TRANSACTION))
            whenCommand(RefundPayment(REFUSED)) {
                expectEvents(PaymentRefunded(REFUSED))
                expectState {
                    status.assert().isEqualTo(PaymentStatus.REFUNDED)
                    reason.assert().isEqualTo(REFUSED)
                }
            }
            whenCommand(RefundPayment(REFUSED)) { expectUnchanged() }
            whenCommand(ConfirmPayment(TRANSACTION)) { expectRejected(PaymentRejections.WRONG_STATUS) }
        }
    }

    @Test
    fun `a requested payment should not be refunded`() {
        paymentSpec {
            given(PaymentRequested(ORDER, AMOUNT))
            whenCommand(RefundPayment(REFUSED)) { expectRejected(PaymentRejections.WRONG_STATUS) }
        }
    }

    @Test
    fun `a requested payment should end confirmed and refunded, or failed`() = paymentSpec {
        whenCommand(RequestPayment(ORDER, AMOUNT)) { expectEventTypes(PaymentRequested::class) }
        ref("requested")
        fork("confirmed") {
            whenCommand(ConfirmPayment(TRANSACTION)) { expectEventTypes(PaymentSucceeded::class) }
            whenCommand(RefundPayment(REFUSED)) { expectEvents(PaymentRefunded(REFUSED)) }
        }
        fork("failed") {
            whenCommand(FailPayment(DECLINED)) { expectEventTypes(PaymentFailed::class) }
            whenCommand(ConfirmPayment(TRANSACTION)) {
                expectRejected(PaymentRejections.WRONG_STATUS)
                expectState { status.assert().isEqualTo(PaymentStatus.FAILED) }
            }
        }
        // The forks left the payment as requested.
        whenCommand(RefundPayment(REFUSED)) {
            expectRejected(PaymentRejections.WRONG_STATUS)
            expectState { status.assert().isEqualTo(PaymentStatus.REQUESTED) }
        }
        fork(from = "requested") {
            whenCommand(FailPayment(DECLINED)) { expectEvents(PaymentFailed(DECLINED)) }
        }
    }

    @Test
    fun `a payment given as succeeded should be refunded`() = paymentSpec {
        givenState(version = 2) {
            onSourcing(PaymentRequested(ORDER, AMOUNT))
            onSourcing(PaymentSucceeded(TRANSACTION))
        }
        whenCommand(RefundPayment(REFUSED), expectedVersion = 2) {
            expectEvents(PaymentRefunded(REFUSED))
            expectState { transactionId.assert().isEqualTo(TRANSACTION) }
        }
    }

    private companion object {
        const val ORDER = "alice-1"
        const val TRANSACTION = "transaction-1"
        const val DECLINED = "declined"
        const val REFUSED = "refused"
        val AMOUNT = BigDecimal("17.00")
    }
}
