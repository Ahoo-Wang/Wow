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

package me.ahoo.wow.rehearsal.example

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.Upgrade
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/** The 9.x order's state, field for field; its id is the ref's in V10. */
class OrderState {
    var items: List<OrderItem> = emptyList()
        private set
    var address: ShippingAddress? = null
        private set
    var totalAmount: BigDecimal = BigDecimal.ZERO
        private set
    var paidAmount: BigDecimal = BigDecimal.ZERO
        private set
    var status: OrderStatus = OrderStatus.CREATED
        private set

    /** What remains to pay. */
    val payable: BigDecimal
        get() = totalAmount.minus(paidAmount)

    fun onSourcing(event: OrderCreated) {
        items = event.items
        address = event.address
        totalAmount = event.items.fold(BigDecimal.ZERO) { total, item -> total + item.totalPrice }
        status = OrderStatus.CREATED
    }

    fun onSourcing(event: AddressChanged) {
        address = event.shippingAddress
    }

    fun onSourcing(event: OrderPaid) {
        paidAmount += event.amount
        if (event.paid) {
            status = OrderStatus.PAID
        }
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderShipped) {
        status = OrderStatus.SHIPPED
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderReceived) {
        status = OrderStatus.RECEIVED
    }
}

/**
 * The 9.x order's decisions. A payment beyond what remains, or of an order no longer created, is rejected: the 9.x
 * `OrderOverPaid` and `OrderPayDuplicated` events have no V10 counterpart in this port.
 */
@AggregateRoot
class Order(private val state: OrderState) {
    @OnCommand(produces = [OrderCreated::class])
    fun onCommand(command: CreateOrder, scope: DecideScope): Decision {
        if (command.items.isEmpty() || command.items.any { it.quantity <= 0 || it.price.signum() <= 0 }) {
            return Decision.reject(Rejections.INVALID_ITEMS, "The order's items are not valid: ${command.items}.")
        }
        val items = command.items.map { OrderItem(scope.ids.next(), it.productId, it.price, it.quantity) }
        return Decision.events(OrderCreated(items, command.address, command.fromCart))
    }

    @OnCommand(produces = [AddressChanged::class])
    fun onCommand(command: ChangeAddress): Decision = whenStatus(OrderStatus.CREATED, "change its address") {
        Decision.events(AddressChanged(command.shippingAddress))
    }

    @OnCommand(produces = [OrderPaid::class])
    fun onCommand(command: PayOrder): Decision = whenStatus(OrderStatus.CREATED, "be paid") {
        if (command.amount.signum() <= 0 || command.amount > state.payable) {
            Decision.reject(Rejections.WRONG_AMOUNT, "${state.payable} remains to pay, not ${command.amount}.")
        } else {
            Decision.events(OrderPaid(command.amount, command.amount.compareTo(state.payable) == 0))
        }
    }

    @Suppress("UnusedParameter")
    @OnCommand(produces = [OrderShipped::class])
    fun onCommand(command: ShipOrder): Decision = whenStatus(OrderStatus.PAID, "be shipped") {
        Decision.events(OrderShipped())
    }

    @Suppress("UnusedParameter")
    @OnCommand(produces = [OrderReceived::class])
    fun onCommand(command: ReceiptOrder): Decision = whenStatus(OrderStatus.SHIPPED, "be received") {
        Decision.events(OrderReceived())
    }

    private inline fun whenStatus(required: OrderStatus, action: String, decide: () -> Decision): Decision =
        if (state.status == required) {
            decide()
        } else {
            Decision.reject(Rejections.WRONG_STATUS, "The order is ${state.status}: it cannot $action.")
        }

    object Rejections {
        @ErrorCode(description = "An order has items, each of a positive quantity and price.")
        const val INVALID_ITEMS = "invalid-items"

        @ErrorCode(description = "The order is not in the status the command needs.", status = 409)
        const val WRONG_STATUS = "wrong-status"

        @ErrorCode(description = "A payment is positive and at most what remains to pay.")
        const val WRONG_AMOUNT = "wrong-amount"
    }
}

/** The 9.x `order_created` (revision 1 in V10) named the order in `orderId`, which the ref is now. */
@Upgrade(event = OrderCreated::class, from = 1)
fun upgradeOrderCreatedFrom1(node: ObjectNode): ObjectNode = node.apply { remove("orderId") }
