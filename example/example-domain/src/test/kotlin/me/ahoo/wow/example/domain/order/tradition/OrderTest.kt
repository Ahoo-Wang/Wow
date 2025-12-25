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
package me.ahoo.wow.example.domain.order.tradition

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.event.AggregateDeleted
import me.ahoo.wow.command.CommandValidationException
import me.ahoo.wow.event.DomainEventException
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
import me.ahoo.wow.example.api.order.ShippingAddress
import me.ahoo.wow.example.domain.order.CreateOrderSpec
import me.ahoo.wow.example.domain.order.DefaultCreateOrderSpec
import me.ahoo.wow.example.domain.order.DefaultCreateOrderSpec.InventoryShortageException
import me.ahoo.wow.example.domain.order.DefaultCreateOrderSpec.PriceInconsistencyException
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.example.domain.order.OrderFixture.SHIPPING_ADDRESS
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.example.domain.order.infra.InventoryService
import me.ahoo.wow.example.domain.order.infra.PricingService
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.command.IllegalAccessDeletedAggregateException
import me.ahoo.wow.test.aggregate.VerifiedStage
import me.ahoo.wow.test.aggregateVerifier
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.math.BigDecimal

internal class OrderTest {

    private fun mockCreateOrder(): VerifiedStage<OrderState> {
        val tenantId = generateGlobalId()

        val orderItem = CreateOrder.Item(
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        val orderItems = listOf(orderItem)
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return orderItems.filter { it.productId == productId }.map { it.quantity }.first().toMono()
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return orderItems.filter { it.productId == productId }.map { it.price }.first().toMono()
            }
        }
        return aggregateVerifier<Order, OrderState>(tenantId = tenantId)
            .inject(DefaultCreateOrderSpec(inventoryService, pricingService))
            .given()
            .whenCommand(CreateOrder(orderItems, SHIPPING_ADDRESS, false))
            .expectEventType(OrderCreated::class)
            .expectStateAggregate {
                aggregateId.tenantId.assert().isEqualTo(tenantId)
            }
            .expectState {
                id.assert().isNotNull()
                assert().isNotNull()
                address.assert().isEqualTo(SHIPPING_ADDRESS)
                verifyItems(items, orderItems)
                status.assert().isEqualTo(OrderStatus.CREATED)
            }.expect {
                exchange.getCommandResult().size.assert().isOne()
                exchange.getCommandResult().assert().hasSize(1)
                val result = exchange.getCommandResult<BigDecimal>(OrderState::totalAmount.name)
                result.assert().isEqualTo(orderItem.price.multiply(BigDecimal.valueOf(orderItem.quantity.toLong())))
            }
            .verify()
    }

    fun verifyItems(orderItems: List<OrderItem>, createOrderItems: List<CreateOrder.Item>) {
        orderItems.assert().hasSize(createOrderItems.size)
        orderItems.forEachIndexed { index, orderItem ->
            val createOrderItem = createOrderItems[index]
            orderItem.productId.assert().isEqualTo(createOrderItem.productId)
            orderItem.price.assert().isEqualTo(createOrderItem.price)
            orderItem.quantity.assert().isEqualTo(createOrderItem.quantity)
        }
    }

    /**
     * 创建订单
     */
    @Test
    fun createOrder() {
        mockCreateOrder()
    }

    /**
     * 创建订单-非中国的收货地址
     */
    @Test
    fun createOrderGivenNonChinaAddress() {
        val orderItem = CreateOrder.Item(
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        val orderItems = listOf(orderItem)
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return orderItems.filter { it.productId == productId }.map { it.quantity }.first().toMono()
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return orderItems.filter { it.productId == productId }.map { it.price }.first().toMono()
            }
        }
        aggregateVerifier<Order, OrderState>()
            .inject(DefaultCreateOrderSpec(inventoryService, pricingService))
            .given()
            .whenCommand(CreateOrder(orderItems, ShippingAddress("US", "US", "US", "US", ""), false))
            .expectErrorType(IllegalArgumentException::class)
            .expectStateAggregate {
                initialized.assert().isFalse()
            }.verify()
    }

    /**
     * 创建订单-空订单项
     */
    @Test
    fun createOrderGivenEmptyItems() {
        aggregateVerifier<Order, OrderState>()
            .inject(mockk<CreateOrderSpec>(), "createOrderSpec")
            .given()
            .whenCommand(CreateOrder(listOf(), SHIPPING_ADDRESS, false))
            .expectErrorType(CommandValidationException::class)
            .expectStateAggregate {
                /*
                 * 该聚合对象处于未初始化状态，即该聚合未创建成功.
                 */
                initialized.assert().isFalse()
            }.verify()
    }

    /**
     * 创建订单-库存不足
     */
    @Test
    fun createOrderWhenInventoryShortage() {
        val orderItem = CreateOrder.Item(
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        val orderItems = listOf(orderItem)
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return orderItems.filter { it.productId == productId }
                    /*
                     * 模拟库存不足
                     */
                    .map { it.quantity - 1 }.first().toMono()
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return orderItems.filter { it.productId == productId }.map { it.price }.first().toMono()
            }
        }

        aggregateVerifier<Order, OrderState>()
            .inject(DefaultCreateOrderSpec(inventoryService, pricingService))
            .given()
            .whenCommand(CreateOrder(orderItems, SHIPPING_ADDRESS, false))
            /*
             * 期望：库存不足异常.
             */
            .expectErrorType(InventoryShortageException::class)
            .expectStateAggregate {
                /*
                 * 该聚合对象处于未初始化状态，即该聚合未创建成功.
                 */
                initialized.assert().isFalse()
            }.verify()
    }

    /**
     * 创建订单-下单价格与当前价格不一致
     */
    @Test
    fun createOrderWhenPriceInconsistency() {
        val orderItem = CreateOrder.Item(
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        val orderItems = listOf(orderItem)
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return orderItems.filter { it.productId == productId }.map { it.quantity }.first().toMono()
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return orderItems.filter { it.productId == productId }
                    /*
                     * 模拟下单价格、商品定价不一致
                     */
                    .map { it.price.plus(BigDecimal.valueOf(1)) }.first().toMono()
            }
        }
        aggregateVerifier<Order, OrderState>()
            .inject(DefaultCreateOrderSpec(inventoryService, pricingService))
            .given()
            .whenCommand(CreateOrder(orderItems, SHIPPING_ADDRESS, false))
            /*
             * 期望：价格不一致异常.
             */
            .expectErrorType(PriceInconsistencyException::class).verify()
    }

    private fun mockPayOrder(): VerifiedStage<OrderState> {
        val verifiedStage = mockCreateOrder()
        val previousState = verifiedStage.stateRoot
        val payOrder = PayOrder(
            previousState.id,
            previousState.totalAmount,
        )

        return verifiedStage
            .then()
            .given()
            /*
             * 2. 当接收到命令
             */
            .whenCommand(payOrder)
            /*
             * 3.1 期望将会产生1个事件
             */
            .expectEventCount(1)
            /*
             * 3.2 期望将会产生一个 OrderPaid 事件 (3.1 可以不需要)
             */
            .expectEventType(OrderPaid::class)
            /*
             * 3.3 期望产生的事件状态
             */
            .expectEventBody<OrderPaid> {
                amount.assert().isEqualTo(payOrder.amount)
            }
            /*
             * 4. 期望当前聚合状态
             */
            .expectState {
                address.assert().isEqualTo(SHIPPING_ADDRESS)
                paidAmount.assert().isEqualTo(payOrder.amount)
                status.assert().isEqualTo(OrderStatus.PAID)
            }
            /*
             * 完成测试编排后，验证期望.
             */
            .verify()
    }

    /**
     * 支付订单
     */
    @Test
    fun payOrder() {
        mockPayOrder()
    }

    /**
     * 重复支付订单
     */
    @Test
    fun payOrderWhenDuplicated() {
        val verifiedStage = mockPayOrder()
        val previousState = verifiedStage.stateRoot
        val payOrder = PayOrder(
            previousState.id,
            previousState.totalAmount,
        )
        verifiedStage
            .then()
            .given()
            .whenCommand(payOrder)
            .expectErrorType(DomainEventException::class)
            .expectEventType(OrderPayDuplicated::class)
            .expectEventBody<OrderPayDuplicated> {
                paymentId.assert().isEqualTo(payOrder.paymentId)
            }
            .verify()
    }

    /**
     * 支付订单-超付
     */
    @Test
    fun payOrderWhenOverPay() {
        val verifiedStage = mockCreateOrder()
        val previousState = verifiedStage.stateRoot
        val payOrder = PayOrder(
            previousState.id,
            previousState.totalAmount.plus(
                BigDecimal.valueOf(1),
            ),
        )
        verifiedStage
            .then()
            .given()
            /*
             * 2. 处理 PayOrder 命令
             */
            .whenCommand(payOrder)
            /*
             * 3.1 期望将会产生俩个事件分别是： OrderPaid、OrderOverPaid
             */
            .expectEventType(OrderPaid::class, OrderOverPaid::class)
            /*
             * 3.2 期望产生的事件状态
             */
            .expectEventIterator {
                val orderPaid = nextEventBody<OrderPaid>()
                orderPaid.paid.assert().isTrue()
                val orderOverPaid = nextEventBody<OrderOverPaid>()
                orderOverPaid.overPay.assert().isEqualTo(payOrder.amount.minus(previousState.totalAmount))
            }
            /*
             * 4. 期望当前聚合状态
             */
            .expectState {
                paidAmount.assert().isEqualTo(previousState.totalAmount)
                status.assert().isEqualTo(OrderStatus.PAID)
            }
            .verify()
    }

    fun mockShip(): VerifiedStage<OrderState> {
        val verifiedStage = mockPayOrder()
        val shipOrder = ShipOrder(verifiedStage.stateRoot.id)
        return verifiedStage
            .then().given()
            .whenCommand(shipOrder)
            .expectEventType(OrderShipped::class)
            /*
             * 4. 期望当前聚合状态
             */
            .expectState {
                status.assert().isEqualTo(OrderStatus.SHIPPED)
            }
            .verify()
    }

    /**
     * 发货
     */
    @Test
    fun ship() {
        mockShip()
    }

    @Test
    fun receiptOrder() {
        val verifiedStage = mockShip()
        verifiedStage.then().given()
            .whenCommand(ReceiptOrder(id = verifiedStage.stateRoot.id))
            .expectNoError()
            .expectEventType(OrderReceived::class)
            .expectState {
                status.assert().isEqualTo(OrderStatus.RECEIVED)
            }
            .verify()
    }

    @Test
    fun shipGivenUnpaid() {
        val verifiedStage = mockCreateOrder()
        val shipOrder = ShipOrder(verifiedStage.stateRoot.id)
        verifiedStage.then().given()
            .whenCommand(shipOrder)
            .expectErrorType(IllegalStateException::class)
            .expectState {
                /*
                 * 验证聚合状态[未]发生变更.
                 */
                paidAmount.assert().isEqualTo(BigDecimal.ZERO)
                status.assert().isEqualTo(OrderStatus.CREATED)
            }
            .verify()
    }

    @Test
    fun changeAddress() {
        val verifiedStage = mockCreateOrder()
        val changeAddress = ChangeAddress(
            shippingAddress = ShippingAddress("上海市", "上海市", "浦东新区", "张江高科", ""),
        )
        verifiedStage.then().given()
            .whenCommand(changeAddress)
            .expectEventType(AddressChanged::class)
            .expectState {
                address.assert().isEqualTo(changeAddress.shippingAddress)
            }
            .verify()
    }

    private fun mockDeleteOrder(): VerifiedStage<OrderState> {
        return mockCreateOrder().then().given()
            .whenCommand(DefaultDeleteAggregate)
            .expectEventType(AggregateDeleted::class)
            .expectStateAggregate {
                deleted.assert().isTrue()
            }
            .verify()
    }

    @Test
    fun deleteOrder() {
        mockDeleteOrder()
    }

    @Test
    fun deleteGivenDeleted() {
        val verifiedStageAfterDelete = mockDeleteOrder()
        verifiedStageAfterDelete.then().given()
            .whenCommand(DefaultDeleteAggregate)
            .expectErrorType(IllegalAccessDeletedAggregateException::class)
            .expectError<IllegalAccessDeletedAggregateException> {
                aggregateId.assert().isEqualTo(verifiedStageAfterDelete.stateAggregate.aggregateId)
            }.expectStateAggregate {
                deleted.assert().isTrue()
            }
            .verify()
    }
}
