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
import me.ahoo.wow.test.aggregateSpecTests
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

/** The payment's state machine on the kernel, through the model the compiler generated. */
class PaymentSpec {
    private fun paymentSpec(block: AggregateSpec<PaymentState>.() -> Unit) =
        aggregateSpec(paymentModel(), id = ORDER, block = block)

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
            whenCommand(FailPayment("too late")) { expectRejected(Payment.Rejections.WRONG_STATUS) }
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
            whenCommand(ConfirmPayment(TRANSACTION)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
            whenCommand(RefundPayment("no")) { expectRejected(Payment.Rejections.WRONG_STATUS) }
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
            whenCommand(ConfirmPayment(TRANSACTION)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
            whenCommand(FailPayment(DECLINED)) {
                expectRejected(Payment.Rejections.WRONG_STATUS) {
                    message.assert().isEqualTo("The payment is REFUNDED: it cannot fail.")
                }
            }
        }
    }

    @Test
    fun `a requested payment should not be refunded`() {
        paymentSpec {
            given(PaymentRequested(ORDER, AMOUNT))
            whenCommand(RefundPayment(REFUSED)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
        }
    }

    // region fork
    @Test
    fun `a requested payment should end confirmed and refunded, or failed`() = paymentSpec {
        givenCommands(RequestPayment(ORDER, AMOUNT))
        ref("requested")
        fork("confirmed") {
            givenCommands(ConfirmPayment(TRANSACTION))
            whenCommand(RefundPayment(REFUSED)) { expectEvents(PaymentRefunded(REFUSED)) }
        }
        fork("failed") {
            givenCommands(FailPayment(DECLINED))
            whenCommand(ConfirmPayment(TRANSACTION)) {
                expectRejected(Payment.Rejections.WRONG_STATUS)
                expectState { status.assert().isEqualTo(PaymentStatus.FAILED) }
            }
        }
        // The forks left the payment as requested.
        whenCommand(RefundPayment(REFUSED)) {
            expectRejected(Payment.Rejections.WRONG_STATUS)
            expectState { status.assert().isEqualTo(PaymentStatus.REQUESTED) }
        }
        fork(from = "requested") {
            whenCommand(FailPayment(DECLINED)) { expectEvents(PaymentFailed(DECLINED)) }
        }
    }
    // endregion

    // region tree
    /**
     * The same lifecycle as a tree of tests: each command is a test and each fork a container, forked from the checks
     * of the request, so a failing branch is reported on its own and the others still run.
     */
    @TestFactory
    fun `a payment's lifecycle`() = aggregateSpecTests(paymentModel(), id = ORDER) {
        whenCommand(RequestPayment(ORDER, AMOUNT)) {
            expectEventTypes(PaymentRequested::class)
            fork("confirmed") {
                whenCommand(ConfirmPayment(TRANSACTION)) {
                    expectEventTypes(PaymentSucceeded::class)
                    fork("refunded") {
                        whenCommand(RefundPayment(REFUSED)) { expectEvents(PaymentRefunded(REFUSED)) }
                        whenCommand(ConfirmPayment(TRANSACTION)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
                    }
                }
                whenCommand(FailPayment("too late")) { expectRejected(Payment.Rejections.WRONG_STATUS) }
            }
            fork("failed") {
                whenCommand(FailPayment(DECLINED)) { expectEvents(PaymentFailed(DECLINED)) }
                whenCommand(RefundPayment(REFUSED)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
            }
        }
        whenCommand(RefundPayment(REFUSED)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
    }
    // endregion

    // region given-state
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
    // endregion

    private companion object {
        const val ORDER = "alice-1"
        const val TRANSACTION = "transaction-1"
        const val DECLINED = "declined"
        const val REFUSED = "refused"
        val AMOUNT = BigDecimal("17.00")
    }
}
