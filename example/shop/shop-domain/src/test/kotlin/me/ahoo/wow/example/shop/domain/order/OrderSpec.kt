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

package me.ahoo.wow.example.shop.domain.order

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.shop.api.order.AddressChanged
import me.ahoo.wow.example.shop.api.order.ChangeAddress
import me.ahoo.wow.example.shop.api.order.CreateOrder
import me.ahoo.wow.example.shop.api.order.OrderOverPaid
import me.ahoo.wow.example.shop.api.order.OrderPaid
import me.ahoo.wow.example.shop.api.order.OrderPayDuplicated
import me.ahoo.wow.example.shop.api.order.OrderReceived
import me.ahoo.wow.example.shop.api.order.OrderShipped
import me.ahoo.wow.example.shop.api.order.OrderStatus
import me.ahoo.wow.example.shop.api.order.PayOrder
import me.ahoo.wow.example.shop.api.order.ReceiptOrder
import me.ahoo.wow.example.shop.api.order.ShipOrder
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.APPLE_PRICE
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.HOME
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.OFFICE
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.ORDER_ID
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.createOrder
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.model
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.orderCreated
import me.ahoo.wow.example.shop.domain.order.OrderFixtures.total
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.KernelRejections
import me.ahoo.wow.kernel.test.AggregateSpec
import me.ahoo.wow.kernel.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The order on the kernel, through the generated model with stub inventory and pricing services. */
class OrderSpec {
    private fun orderSpec(block: AggregateSpec<OrderState>.() -> Unit) = aggregateSpec(
        model,
        id = ORDER_ID,
        block = block
    )

    private fun AggregateSpec<OrderState>.paid() {
        given(orderCreated)
        given(OrderPaid(total, true))
    }

    @Test
    fun `placing an order should create it and answer its total`() = orderSpec {
        whenCommand(createOrder) {
            expectEvents(orderCreated)
            (outcome as CommandOutcome.Committed).results.assert()
                .isEqualTo(mapOf(Order.TOTAL_AMOUNT_RESULT to total))
            expectState {
                status.assert().isEqualTo(OrderStatus.CREATED)
                totalAmount.assert().isEqualByComparingTo(total)
                address.assert().isEqualTo(HOME)
                items.assert().hasSize(2)
            }
        }
    }

    @Test
    fun `an order without items should be rejected`() = orderSpec {
        whenCommand(createOrder.copy(items = emptyList())) { expectRejected(OrderRejections.NO_ITEMS) }
    }

    @Test
    fun `an order at a price that changed should be rejected`() = orderSpec {
        val stale = createOrder.copy(items = listOf(CreateOrder.Item("apple", BigDecimal("2.00"), 1)))
        whenCommand(stale) { expectRejected(OrderRejections.PRICE_CHANGED) }
    }

    @Test
    fun `an order for more than the stock should be rejected`() = orderSpec {
        val tooMany = createOrder.copy(items = listOf(CreateOrder.Item("apple", APPLE_PRICE, 11)))
        whenCommand(tooMany) { expectRejected(OrderRejections.INSUFFICIENT_INVENTORY) }
    }

    @Test
    fun `an order should be placed only once`() = orderSpec {
        given(orderCreated)
        whenCommand(createOrder) { expectRejected(KernelRejections.EXISTS) }
    }

    @Test
    fun `an order should take a new address until it is paid`() = orderSpec {
        given(orderCreated)
        whenCommand(ChangeAddress(OFFICE)) {
            expectEvents(AddressChanged(OFFICE))
            expectState { address.assert().isEqualTo(OFFICE) }
        }
    }

    @Test
    fun `a paid order should refuse a new address`() = orderSpec {
        paid()
        whenCommand(ChangeAddress(OFFICE)) { expectRejected(OrderRejections.WRONG_STATUS) }
    }

    @Test
    fun `a command for an order that was never placed should be refused`() = orderSpec {
        whenCommand(ChangeAddress(OFFICE)) { expectRejected(KernelRejections.NOT_FOUND) }
    }

    @Test
    fun `paying the whole amount should make the order paid`() = orderSpec {
        given(orderCreated)
        whenCommand(PayOrder("payment-1", total)) {
            expectEvents(OrderPaid(total, true))
            expectState {
                status.assert().isEqualTo(OrderStatus.PAID)
                paidAmount.assert().isEqualByComparingTo(total)
            }
        }
    }

    @Test
    fun `paying part of the amount should leave the order payable`() = orderSpec {
        given(orderCreated)
        whenCommand(PayOrder("payment-1", BigDecimal("4.00"))) {
            expectEvents(OrderPaid(BigDecimal("4.00"), false))
            expectState { status.assert().isEqualTo(OrderStatus.CREATED) }
        }
        whenCommand(PayOrder("payment-2", BigDecimal("5.00"))) {
            expectEvents(OrderPaid(BigDecimal("5.00"), true))
            expectState { status.assert().isEqualTo(OrderStatus.PAID) }
        }
    }

    @Test
    fun `paying too much should pay the order and record the excess`() = orderSpec {
        given(orderCreated)
        whenCommand(PayOrder("payment-1", BigDecimal("10.00"))) {
            expectEvents(OrderPaid(total, true), OrderOverPaid("payment-1", BigDecimal("1.00")))
            expectState { status.assert().isEqualTo(OrderStatus.PAID) }
        }
    }

    @Test
    fun `paying an order that is paid should record the payment for a refund`() = orderSpec {
        paid()
        whenCommand(PayOrder("payment-2", total)) {
            expectEventTypes(OrderPayDuplicated::class.java)
            expectState {
                status.assert().isEqualTo(OrderStatus.PAID)
                paidAmount.assert().isEqualByComparingTo(total)
            }
        }
    }

    @Test
    fun `a paid order should ship`() = orderSpec {
        paid()
        whenCommand(ShipOrder) {
            expectEvents(OrderShipped)
            expectState { status.assert().isEqualTo(OrderStatus.SHIPPED) }
        }
    }

    @Test
    fun `an order that is not paid should not ship`() = orderSpec {
        given(orderCreated)
        whenCommand(ShipOrder) { expectRejected(OrderRejections.WRONG_STATUS) }
    }

    @Test
    fun `a shipped order should be received`() = orderSpec {
        paid()
        given(OrderShipped)
        whenCommand(ReceiptOrder) {
            expectEvents(OrderReceived)
            expectState { status.assert().isEqualTo(OrderStatus.RECEIVED) }
        }
    }

    @Test
    fun `an order that is not shipped should not be received`() = orderSpec {
        paid()
        whenCommand(ReceiptOrder) { expectRejected(OrderRejections.WRONG_STATUS) }
    }
}
