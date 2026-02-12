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
 * Order Aggregate.
 *
 * 为了防止命令处理函数不小心修改聚合状态，可拆分成聚合状态类存放聚合状态.
 *
 * [me.ahoo.wow.api.annotation.AggregateRoot] 注解是可选的标记.
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
     * 此时的聚合处于空状态,也可以理解为该方法是一个聚合工厂,订阅首个聚合命令并发布首个领域事件.
     *
     *
     * [me.ahoo.wow.modeling.annotation.OnCommand] 注解是可选的,约定命令默认命令函数为名 `onCommand`
     *
     *
     * ### Kotlin 协程 Style
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
     * @param specification 该外部服务将会通过 IOC 容器自动注入进来
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

    /**
     * ***** 重要：命令处理函数不直接修改聚合状态，而是通过执行完业务验证逻辑后返回领域事件来由聚合朔源事件修改聚合状态，并且发布到事件总线 *****.
     *
     * 命令处理函数职责:
     * 1. 验证命令参数是否符合业务规范
     * 2. 验证当前当前状态是否可以执行该命令
     * 3. 返回事件，用于对外发布聚合状态变更事件(可对外发布多个事件)
     *
     */
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
     * *订单服务* 订阅 *支付服务* 发布的集成事件 `PaymentOrderPaid`，适配成 *订单服务* 限界上下文 `PayOrder` 命令.
     *
     * @see PaymentOrderPaid
     */
    fun onCommand(payOrder: PayOrder): Iterable<*> {
        if (OrderStatus.CREATED != state.status) {
            if (log.isWarnEnabled) {
                log.warn("The current order[{}] status[{}] cannot pay order.", state.id, state.status)
            }
            /*
             * 订单领域不会关注该事件（修改订单状态），但需要发布该事件。支付服务需要关注该事件，为客户执行退款操作。
             */
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
        /*
         * 该订单超付事件会由支付服务订阅，并给客户退款。
         */
        val overPaid = OrderOverPaid(payOrder.paymentId, overPay)
        /*
         * OnCommand、OnEvent 函数可返回多个领域事件：已支付成功事件，以及订单超付事件.
         * 事件发布顺序与该List保持一致
         */
        return listOf(orderPaid, overPaid)
    }
}
