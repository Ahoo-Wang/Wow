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
package me.ahoo.wow.example.domain.order

import io.mockk.mockk
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.event.AggregateDeleted
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
import me.ahoo.wow.example.api.order.PayOrder
import me.ahoo.wow.example.api.order.ReceiptOrder
import me.ahoo.wow.example.api.order.ShipOrder
import me.ahoo.wow.example.api.order.ShippingAddress
import me.ahoo.wow.example.domain.order.DefaultCreateOrderSpec.InventoryShortageException
import me.ahoo.wow.example.domain.order.DefaultCreateOrderSpec.PriceInconsistencyException
import me.ahoo.wow.example.domain.order.OrderFixture.SHIPPING_ADDRESS
import me.ahoo.wow.example.domain.order.infra.InventoryService
import me.ahoo.wow.example.domain.order.infra.PricingService
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.command.IllegalAccessDeletedAggregateException
import me.ahoo.wow.test.aggregate.VerifiedStage
import me.ahoo.wow.test.aggregateVerifier
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.math.BigDecimal

internal class OrderTest {

    private fun mockCreateOrder(): VerifiedStage<OrderState> {
        val tenantId = GlobalIdGenerator.generateAsString()
        val customerId = GlobalIdGenerator.generateAsString()

        val orderItem = CreateOrder.Item(
            productId = GlobalIdGenerator.generateAsString(),
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
            .`when`(CreateOrder(customerId, orderItems, SHIPPING_ADDRESS, false))
            .expectEventCount(1)
            .expectEventType(OrderCreated::class.java)
            .expectStateAggregate {
                assertThat(it.aggregateId.tenantId, equalTo(tenantId))
            }
            .expectState {
                assertThat(it.id, notNullValue())
                assertThat(it.customerId, equalTo(customerId))
                assertThat(it.address, equalTo(SHIPPING_ADDRESS))
                verifyItems(it.items, orderItems)
                assertThat(it.status, equalTo(OrderStatus.CREATED))
            }
            .verify()
    }

    fun verifyItems(orderItems: List<OrderItem>, createOrderItems: List<CreateOrder.Item>) {
        assertThat(orderItems, hasSize(createOrderItems.size))
        orderItems.forEachIndexed { index, orderItem ->
            val createOrderItem = createOrderItems[index]
            assertThat(orderItem.productId, equalTo(createOrderItem.productId))
            assertThat(orderItem.price, equalTo(createOrderItem.price))
            assertThat(orderItem.quantity, equalTo(createOrderItem.quantity))
        }
    }

    /**
     * 创建订单
     */
    @Test
    fun createOrder() {
        mockCreateOrder()
    }

    @Test
    fun createOrderGivenEmptyItems() {
        val customerId = GlobalIdGenerator.generateAsString()
        aggregateVerifier<Order, OrderState>()
            .inject(mockk<CreateOrderSpec>(), "createOrderSpec")
            .given()
            .`when`(CreateOrder(customerId, listOf(), SHIPPING_ADDRESS, false))
            .expectErrorType(IllegalArgumentException::class.java)
            .expectStateAggregate {
                /*
                 * 该聚合对象处于未初始化状态，即该聚合未创建成功.
                 */
                assertThat(it.initialized, equalTo(false))
            }.verify()
    }

    /**
     * 创建订单-库存不足
     */
    @Test
    fun createOrderWhenInventoryShortage() {
        val customerId = GlobalIdGenerator.generateAsString()
        val orderItem = CreateOrder.Item(
            productId = GlobalIdGenerator.generateAsString(),
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
            .`when`(CreateOrder(customerId, orderItems, SHIPPING_ADDRESS, false))
            /*
             * 期望：库存不足异常.
             */
            .expectErrorType(InventoryShortageException::class.java)
            .expectStateAggregate {
                /*
                 * 该聚合对象处于未初始化状态，即该聚合未创建成功.
                 */
                assertThat(it.initialized, equalTo(false))
            }.verify()
    }

    /**
     * 创建订单-下单价格与当前价格不一致
     */
    @Test
    fun createOrderWhenPriceInconsistency() {
        val customerId = GlobalIdGenerator.generateAsString()
        val orderItem = CreateOrder.Item(
            productId = GlobalIdGenerator.generateAsString(),
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
            .`when`(CreateOrder(customerId, orderItems, SHIPPING_ADDRESS, false))
            /*
             * 期望：价格不一致异常.
             */
            .expectErrorType(PriceInconsistencyException::class.java).verify()
    }

    private fun mockPayOrder(): VerifiedStage<OrderState> {
        val verifiedStage = mockCreateOrder()
        val previousState = verifiedStage.stateRoot
        val payOrder = PayOrder(
            previousState.id,
            GlobalIdGenerator.generateAsString(),
            previousState.totalAmount,
        )

        return verifiedStage
            .then()
            .given()
            /*
             * 2. 当接收到命令
             */
            .`when`(payOrder)
            /*
             * 3.1 期望将会产生1个事件
             */
            .expectEventCount(1)
            /*
             * 3.2 期望将会产生一个 OrderPaid 事件 (3.1 可以不需要)
             */
            .expectEventType(OrderPaid::class.java)
            /*
             * 3.3 期望产生的事件状态
             */
            .expectEventBody<OrderPaid> {
                assertThat(it.amount, equalTo(payOrder.amount))
            }
            /*
             * 4. 期望当前聚合状态
             */
            .expectState {
                assertThat(it.address, equalTo(SHIPPING_ADDRESS))
                assertThat(it.paidAmount, equalTo(payOrder.amount))
                assertThat(it.status, equalTo(OrderStatus.PAID))
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
            GlobalIdGenerator.generateAsString(),
            previousState.totalAmount,
        )
        verifiedStage
            .then()
            .given()
            .`when`(payOrder)
            .expectErrorType(DomainEventException::class.java)
            .expectEventType(OrderPayDuplicated::class.java)
            .expectEventBody<OrderPayDuplicated> {
                assertThat(it.paymentId, equalTo(payOrder.paymentId))
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
            GlobalIdGenerator.generateAsString(),
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
            .`when`(payOrder)
            /*
             * 3.1 期望将会产生俩个事件分别是： OrderPaid、OrderOverPaid
             */
            .expectEventType(OrderPaid::class.java, OrderOverPaid::class.java)
            /*
             * 3.2 期望产生的事件状态
             */
            .expectEventIterator {
                val orderPaid = it.nextEventBody<OrderPaid>()
                assertThat(orderPaid.paid, equalTo(true))
                val orderOverPaid = it.nextEventBody<OrderOverPaid>()
                assertThat(
                    orderOverPaid.overPay,
                    equalTo(payOrder.amount.minus(previousState.totalAmount)),
                )
            }
            /*
             * 4. 期望当前聚合状态
             */
            .expectState {
                assertThat(it.paidAmount, equalTo(previousState.totalAmount))
                assertThat(it.status, equalTo(OrderStatus.PAID))
            }
            .verify()
    }

    fun mockShip(): VerifiedStage<OrderState> {
        val verifiedStage = mockPayOrder()
        val shipOrder = ShipOrder(verifiedStage.stateRoot.id)
        return verifiedStage
            .then().given()
            .`when`(shipOrder)
            .expectEventType(OrderShipped::class.java)
            /*
             * 4. 期望当前聚合状态
             */
            .expectState {
                assertThat(it.status, equalTo(OrderStatus.SHIPPED))
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
            .`when`(ReceiptOrder(id = verifiedStage.stateRoot.id, customerId = verifiedStage.stateRoot.customerId))
            .expectNoError()
            .expectEventType(OrderReceived::class.java)
            .expectState {
                assertThat(it.status, equalTo(OrderStatus.RECEIVED))
            }
            .verify()
    }

    @Test
    fun shipGivenUnpaid() {
        val verifiedStage = mockCreateOrder()
        val shipOrder = ShipOrder(verifiedStage.stateRoot.id)
        verifiedStage.then().given()
            .`when`(shipOrder)
            .expectErrorType(IllegalStateException::class.java)
            .expectState {
                /*
                 * 验证聚合状态[未]发生变更.
                 */
                assertThat(it.paidAmount, equalTo(BigDecimal.ZERO))
                assertThat(it.status, equalTo(OrderStatus.CREATED))
            }
            .verify()
    }

    @Test
    fun changeAddress() {
        val verifiedStage = mockCreateOrder()
        val changeAddress = ChangeAddress(
            id = verifiedStage.stateRoot.id,
            shippingAddress = ShippingAddress("上海市", "上海市", "浦东新区", "张江高科", ""),
        )
        verifiedStage.then().given()
            .`when`(changeAddress)
            .expectEventType(AddressChanged::class.java)
            .expectState {
                assertThat(it.address, equalTo(changeAddress.shippingAddress))
            }
            .verify()
    }

    private fun mockDeleteOrder(): VerifiedStage<OrderState> {
        return mockCreateOrder().then().given()
            .`when`(DefaultDeleteAggregate)
            .expectEventType(AggregateDeleted::class.java)
            .expectStateAggregate {
                assertThat(it.deleted, equalTo(true))
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
            .`when`(DefaultDeleteAggregate)
            .expectErrorType(IllegalAccessDeletedAggregateException::class.java)
            .expectError<IllegalAccessDeletedAggregateException> {
                assertThat(it.aggregateId, equalTo(verifiedStageAfterDelete.stateAggregate.aggregateId))
            }.expectStateAggregate {
                assertThat(it.deleted, equalTo(true))
            }
            .verify()
    }
}
