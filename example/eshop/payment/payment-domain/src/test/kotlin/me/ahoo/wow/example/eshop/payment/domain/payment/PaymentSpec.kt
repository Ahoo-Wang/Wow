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
import me.ahoo.wow.example.eshop.payment.api.payment.PaymentRequested
import me.ahoo.wow.example.eshop.payment.api.payment.RequestPayment
import me.ahoo.wow.kernel.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The payment on the kernel, through the model the compiler generated. */
class PaymentSpec {
    @Test
    fun `requesting a payment should create it`() {
        aggregateSpec(paymentModel(PaymentContext.NAME), id = "payment-1") {
            whenCommand(RequestPayment("order-1", BigDecimal("17.00"))) {
                expectEvents(PaymentRequested("order-1", BigDecimal("17.00")))
                expectState {
                    orderId.assert().isEqualTo("order-1")
                    amount.assert().isEqualByComparingTo("17.00")
                }
            }
        }
    }
}
