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

package me.ahoo.wow.example.eshop.ordering.domain.order

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.reject
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.example.eshop.ordering.api.order.AwaitStockValidation
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.ConfirmOrderStock
import me.ahoo.wow.example.eshop.ordering.api.order.OrderAwaitingValidation
import me.ahoo.wow.example.eshop.ordering.api.order.OrderCancelled
import me.ahoo.wow.example.eshop.ordering.api.order.OrderPaid
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStatus
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStockConfirmed
import me.ahoo.wow.example.eshop.ordering.api.order.OrderStockItemConfirmed
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted
import me.ahoo.wow.example.eshop.ordering.api.order.PayOrder
import me.ahoo.wow.example.eshop.ordering.api.order.ShipOrder
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder

/**
 * A buyer's order and its state machine, after the classic eShop's:
 *
 * ```text
 * SUBMITTED -> AWAITING_VALIDATION -> STOCK_CONFIRMED -> PAID -> SHIPPED
 *     \______________\_____________________\__________-> CANCELLED
 * ```
 *
 * - [SubmitOrder] creates it; the basket context sends it on checkout. The order belongs to its buyer: its owner is the
 *   owner of the checkout's basket, whose scope the basket's saga carries on.
 * - [AwaitStockValidation] moves it on, and [OrderProcessSaga] then reserves its stock in the inventory context.
 * - [ConfirmOrderStock] confirms one product, as [OrderProcessSaga] learns from the receipt of its reservation. The
 *   last one confirms the order's stock and schedules its cancellation once the payment grace period of the
 *   [PaymentPolicy] has passed: a durable scheduled command, written with the events.
 * - [PayOrder] pays it and cancels that schedule; [ShipOrder] ships it.
 * - [CancelOrder] cancels an order not paid yet: the saga's compensation when the inventory rejects a reservation,
 *   or the scheduled command when the grace period has passed. Once the order is paid, the scheduled cancellation is
 *   rejected when it is due, which is a normal guard.
 */
@AggregateRoot
@Description("A buyer's order, from its submission to its shipment")
class Order(private val state: OrderState) {
    // region submit
    fun onCommand(command: SubmitOrder): OrderSubmitted = OrderSubmitted(command.items)
    // endregion

    /** Moving on an order that is no longer submitted, one cancelled meanwhile say, changes nothing. */
    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    fun onCommand(command: AwaitStockValidation): OrderAwaitingValidation? =
        if (state.status == OrderStatus.SUBMITTED) OrderAwaitingValidation else null

    // region confirm-stock
    /**
     * Confirms one product. A confirmation the order does not wait for changes nothing: one read again from the
     * stock stream, or one that arrives after another product's shortage cancelled the order (that reservation is
     * released when the inventory hears of the cancellation).
     */
    // Decision: the last confirmation produces two events, the item's and the order's.
    @OnCommand(
        produces = [OrderStockItemConfirmed::class, OrderStockConfirmed::class],
        schedules = [CancelOrder::class],
    )
    fun onCommand(command: ConfirmOrderStock, paymentPolicy: PaymentPolicy, scope: DecideScope): Decision {
        val waiting = state.status == OrderStatus.AWAITING_VALIDATION &&
            command.productId in state.products() &&
            command.productId !in state.confirmedProducts
        if (!waiting) {
            return Decision.NoOp
        }
        val confirmed = OrderStockItemConfirmed(command.productId)
        if (state.confirmedProducts + command.productId != state.products()) {
            return Decision.events(confirmed)
        }
        scope.schedule(
            PAYMENT_GRACE_PERIOD,
            ScheduleRule.after(paymentPolicy.gracePeriod),
            CancelOrder(PAYMENT_TIMEOUT)
        )
        return Decision.events(confirmed, OrderStockConfirmed)
    }
    // endregion

    fun onCommand(command: PayOrder, scope: DecideScope): OrderPaid {
        requireStatus(OrderStatus.STOCK_CONFIRMED, "be paid")
        if (command.amount.compareTo(state.totalAmount) != 0) {
            reject(Rejections.WRONG_AMOUNT, "The order costs ${state.totalAmount}, not ${command.amount}.")
        }
        scope.cancelSchedule(PAYMENT_GRACE_PERIOD)
        return OrderPaid(command.paymentId, command.amount)
    }

    @Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
    fun onCommand(command: ShipOrder): OrderShipped {
        requireStatus(OrderStatus.PAID, "be shipped")
        return OrderShipped
    }

    /** Cancelling a cancelled order changes nothing: the saga cancels once for each product the inventory refused. */
    fun onCommand(command: CancelOrder, scope: DecideScope): OrderCancelled? = when (state.status) {
        OrderStatus.CANCELLED -> null
        OrderStatus.PAID, OrderStatus.SHIPPED -> reject(
            Rejections.WRONG_STATUS,
            "The order is ${state.status}: only an order not paid yet can be cancelled.",
        )

        else -> {
            scope.cancelSchedule(PAYMENT_GRACE_PERIOD)
            OrderCancelled(command.reason)
        }
    }

    /** Rejects the command unless the order is [required]. */
    private fun requireStatus(required: OrderStatus, action: String) {
        if (state.status != required) {
            val message = "The order is ${state.status}: only a $required order can $action."
            reject(Rejections.WRONG_STATUS, message)
        }
    }

    companion object {
        /** The key of the order's scheduled cancellation once its stock is confirmed. */
        const val PAYMENT_GRACE_PERIOD = "payment-grace-period"

        /** The reason of that cancellation. */
        const val PAYMENT_TIMEOUT = "The order was not paid within its payment grace period."
    }

    // region rejections
    /**
     * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
     * `<context>.order.<code>`, as its peers read them from the api's generated contract.
     */
    object Rejections {
        @ErrorCode(description = "The order's status does not allow the command.", status = 409)
        const val WRONG_STATUS = "wrong-status"

        @ErrorCode(description = "A payment pays the order's total amount.")
        const val WRONG_AMOUNT = "wrong-amount"
    }
    // endregion
}
