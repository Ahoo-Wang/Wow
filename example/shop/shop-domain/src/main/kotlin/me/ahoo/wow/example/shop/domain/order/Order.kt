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

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.example.shop.api.order.AddressChanged
import me.ahoo.wow.example.shop.api.order.ChangeAddress
import me.ahoo.wow.example.shop.api.order.CreateOrder
import me.ahoo.wow.example.shop.api.order.OrderCreated
import me.ahoo.wow.example.shop.api.order.OrderItem
import me.ahoo.wow.example.shop.api.order.OrderOverPaid
import me.ahoo.wow.example.shop.api.order.OrderPaid
import me.ahoo.wow.example.shop.api.order.OrderPayDuplicated
import me.ahoo.wow.example.shop.api.order.OrderPayment
import me.ahoo.wow.example.shop.api.order.OrderReceived
import me.ahoo.wow.example.shop.api.order.OrderRejections
import me.ahoo.wow.example.shop.api.order.OrderShipped
import me.ahoo.wow.example.shop.api.order.OrderStatus
import me.ahoo.wow.example.shop.api.order.PayOrder
import me.ahoo.wow.example.shop.api.order.ReceiptOrder
import me.ahoo.wow.example.shop.api.order.ShipOrder

/** A customer's order, from placing it to receiving it. */
@AggregateRoot
@Description("A customer's order, from placing it to receiving it")
class Order(private val state: OrderState) {
    /**
     * Places the order once every item's price is still the product's and its stock covers the quantity. The
     * services are parameters of the generated factory, bound once when the host builds the model (decision D49); the
     * function suspends on them. The order's total goes back to the caller as the result `totalAmount`
     * ([DecideScope.putResult]); it is not persisted, and a repeated request is not answered it again (decision D51).
     */
    suspend fun onCommand(
        command: Command<CreateOrder>,
        inventoryService: InventoryService,
        pricingService: PricingService,
        scope: DecideScope,
    ): Decision {
        val placing = command.body
        if (placing.items.isEmpty()) {
            return Decision.reject(OrderRejections.NO_ITEMS, "An order needs at least one item.")
        }
        placing.items.forEach { item ->
            val price = pricingService.getProductPrice(item.productId)
            if (price.compareTo(item.price) != 0) {
                return Decision.reject(
                    OrderRejections.PRICE_CHANGED,
                    "The price of ${item.productId} is now $price, not ${item.price}.",
                )
            }
            val inventory = inventoryService.getInventory(item.productId)
            if (item.quantity > inventory) {
                return Decision.reject(
                    OrderRejections.INSUFFICIENT_INVENTORY,
                    "Only $inventory of ${item.productId} are in stock, not ${item.quantity}.",
                )
            }
        }
        val created = OrderCreated(
            // Item ids derive from the order's id, so deciding again (on a version conflict) gives the same events.
            items = placing.items.mapIndexed { index, item ->
                OrderItem("${command.ref.id}-${index + 1}", item.productId, item.price, item.quantity)
            },
            address = placing.address,
            fromCart = placing.fromCart,
        )
        scope.putResult(TOTAL_AMOUNT_RESULT, created.totalAmount())
        return Decision.events(created)
    }

    fun onCommand(command: ChangeAddress): Decision = whenStatus(OrderStatus.CREATED, "change its address") {
        Decision.events(AddressChanged(command.shippingAddress))
    }

    /**
     * Records a payment. A payment the order cannot take is not refused: the payment service already took the money,
     * so the order records [OrderPayDuplicated] and the payment service refunds it, as it refunds the excess of an
     * [OrderOverPaid].
     */
    fun onCommand(command: PayOrder): List<OrderPayment> {
        if (state.status != OrderStatus.CREATED) {
            return listOf(
                OrderPayDuplicated(command.paymentId, "The order ${state.id} is ${state.status}, not payable."),
            )
        }
        val payable = state.totalAmount - state.paidAmount
        if (command.amount <= payable) {
            return listOf(OrderPaid(command.amount, command.amount.compareTo(payable) == 0))
        }
        return listOf(OrderPaid(payable, true), OrderOverPaid(command.paymentId, command.amount - payable))
    }

    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    fun onCommand(command: ShipOrder): Decision = whenStatus(OrderStatus.PAID, "ship it") {
        Decision.events(OrderShipped)
    }

    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    fun onCommand(command: ReceiptOrder): Decision = whenStatus(OrderStatus.SHIPPED, "receive it") {
        Decision.events(OrderReceived)
    }

    private inline fun whenStatus(required: OrderStatus, action: String, decide: () -> Decision): Decision =
        if (state.status == required) {
            decide()
        } else {
            Decision.reject(
                OrderRejections.WRONG_STATUS,
                "The order ${state.id} is ${state.status}: only a $required order can $action.",
            )
        }

    companion object {
        /** The result [CreateOrder] answers the caller with: the order's total amount. */
        const val TOTAL_AMOUNT_RESULT = "totalAmount"
    }
}
