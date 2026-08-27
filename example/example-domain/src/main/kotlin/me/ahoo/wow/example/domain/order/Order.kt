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
@file:Suppress("unused")

package me.ahoo.wow.example.domain.order

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.CommandResultAccessor
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.example.api.order.AddressChanged
import me.ahoo.wow.example.api.order.ChangeAddress
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderItem
import me.ahoo.wow.example.api.order.OrderOverPaid
import me.ahoo.wow.example.api.order.OrderPaid
import me.ahoo.wow.example.api.order.OrderPayDuplicated
import me.ahoo.wow.example.api.order.OrderReceived
import me.ahoo.wow.example.api.order.OrderShipped
import me.ahoo.wow.example.api.order.OrderStatus
import me.ahoo.wow.example.api.order.PayOrder
import me.ahoo.wow.example.api.order.ReceiptOrder
import me.ahoo.wow.example.api.order.ShipOrder
import me.ahoo.wow.id.GlobalIdGenerator
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * Order aggregate.
 *
 * State is isolated in [OrderState] so command handlers cannot mutate it accidentally.
 *
 * [AggregateRoot] is an optional marker annotation.
 *
 * @author ahoo wang
 * @see me.ahoo.wow.modeling.command.CommandAggregate
 * @see me.ahoo.wow.modeling.state.StateAggregate
 */
@AggregateRoot
@AggregateRoute(resourceName = "sales-order", spaced = true, owner = AggregateRoute.Owner.ALWAYS)
class Order(private val state: OrderState) {
    companion object {
        private val log = LoggerFactory.getLogger(Order::class.java)
    }

    /**
     * Creates an order from its first command while the aggregate is uninitialized.
     * The conventional `onCommand` name makes the command-handler annotation optional.
     *
     * ### Kotlin coroutine style
     * ```kotlin
     *     suspend fun onCommand(
     *         command: CommandMessage<CreateOrder>,
     *         @Name("createOrderSpec") specification: CreateOrderSpec,
     *         commandResultAccessor: CommandResultAccessor
     *     ): OrderCreated {
     *         val createOrder = command.body
     *         require(createOrder.items.isNotEmpty()) {
     *             "items can not be empty."
     *         }
     *         createOrder.items.asFlow().collect {
     *             specification.require(it).awaitSingle()
     *         }
     *         val orderCreated = OrderCreated(
     *             orderId = command.aggregateId.id,
     *             items = createOrder.items.map {
     *                 OrderItem(
     *                     id = GlobalIdGenerator.generateAsString(),
     *                     productId = it.productId,
     *                     price = it.price,
     *                     quantity = it.quantity,
     *                 )
     *             },
     *             address = createOrder.address,
     *             fromCart = createOrder.fromCart,
     *         )
     *         commandResultAccessor.setCommandResult(
     *             OrderState::totalAmount.name,
     *             orderCreated.items.sumOf { it.totalPrice }
     *         )
     *         return orderCreated
     *     }
     * ```
     *
     * @param command The create-order command message.
     * @param specification The creation rules injected by the IoC container.
     * @param commandResultAccessor Records the total amount in the command result.
     */
    fun onCommand(
        command: CommandMessage<CreateOrder>,
        @Name("createOrderSpec") specification: CreateOrderSpec,
        commandResultAccessor: CommandResultAccessor
    ): Mono<OrderCreated> {
        val createOrder = command.body
        require(createOrder.items.isNotEmpty()) {
            "items can not be empty."
        }
        return Flux
            .fromIterable(createOrder.items)
            .flatMap(specification::require)
            .then(
                OrderCreated(
                    orderId = command.aggregateId.id,
                    items = createOrder.items.map {
                        OrderItem(
                            id = GlobalIdGenerator.generateAsString(),
                            productId = it.productId,
                            price = it.price,
                            quantity = it.quantity,
                        )
                    },
                    address = createOrder.address,
                    fromCart = createOrder.fromCart,
                ).toMono().doOnNext { orderCreated ->
                    commandResultAccessor.setCommandResult(
                        OrderState::totalAmount.name,
                        orderCreated.items.sumOf { it.totalPrice }
                    )
                }
            )
    }

    fun onError(
        createOrder: CreateOrder,
        throwable: Throwable,
        eventStream: DomainEventStream?,
    ): Mono<Void> {
        log.error("onError - [{}]", createOrder, throwable)
        return Mono.empty()
    }

    fun onCommand(changeAddress: ChangeAddress): AddressChanged {
        check(OrderStatus.CREATED == state.status) {
            "The current order[${state.id}] status[${state.status}] cannot modify the address"
        }
        return AddressChanged(changeAddress.shippingAddress)
    }

    fun onCommand(shipOrder: ServerCommandExchange<ShipOrder>): OrderShipped {
        check(
            OrderStatus.PAID == state.status
        ) { "The current order[${state.id}] status[${state.status}] cannot ship order." }
        return OrderShipped
    }

    fun onCommand(receiptOrder: ReceiptOrder): OrderReceived {
        check(OrderStatus.SHIPPED == state.status) {
            "The current order[${state.id}] status[${state.status}] cannot receipt order."
        }
        return OrderReceived
    }

    /**
     * Applies a payment-service event after it has been adapted to [PayOrder].
     */
    fun onCommand(payOrder: PayOrder): Iterable<*> {
        if (OrderStatus.CREATED != state.status) {
            if (log.isWarnEnabled) {
                log.warn("The current order[{}] status[{}] cannot pay order.", state.id, state.status)
            }
            /* The order state ignores this event, while the payment service uses it to issue a refund. */
            return listOf(
                OrderPayDuplicated(
                    paymentId = payOrder.paymentId,
                    errorMsg = "The current order[${state.id}] status[${state.status}] cannot pay order.",
                ),
            )
        }

        val currentPayable = state.payable

        if (currentPayable >= payOrder.amount) {
            return listOf(OrderPaid(payOrder.amount, currentPayable == payOrder.amount))
        }
        val overPay = payOrder.amount - currentPayable
        val orderPaid = OrderPaid(currentPayable, true)
        /* The payment service consumes this event to refund the excess amount. */
        val overPaid = OrderOverPaid(payOrder.paymentId, overPay)
        /* Event publication preserves the list order. */
        return listOf(orderPaid, overPaid)
    }
}
