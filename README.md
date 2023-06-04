# Wow

A Modern Reactive CQRS Architecture Microservice development framework based on DDD and EventSourcing.

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://github.com/Ahoo-Wang/Wow/blob/mvp/LICENSE)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/Wow.svg)](https://github.com/Ahoo-Wang/Wow/releases)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/me.ahoo.wow/wow-core/badge.svg)](https://maven-badges.herokuapp.com/maven-central/me.ahoo.wow/wow-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/cfc724df22db4f9387525258c8a59609)](https://app.codacy.com/gh/Ahoo-Wang/Wow/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/Wow/branch/main/graph/badge.svg?token=uloJrLoQir)](https://codecov.io/gh/Ahoo-Wang/Wow)
[![Integration Test Status](https://github.com/Ahoo-Wang/Wow/actions/workflows/integration-test.yml/badge.svg)](https://github.com/Ahoo-Wang/Wow)
[![Awesome Kotlin Badge](https://kotlin.link/awesome-kotlin.svg)](https://github.com/KotlinBy/awesome-kotlin)

**Domain-Driven** | **Event-Driven** | **Test-Driven** | **Declarative-Design** ｜ **Reactive Programming** ｜ **Command Query Responsibility Segregation** ｜ **Event Sourcing**

## Architecture

<p align="center" style="text-align:center">
  <img src="./document/design/assets/Architecture.svg" alt="Wow-Architecture"/>
</p>

### Event Sourcing

<p align="center" style="text-align:center">
  <img src="./document/design/assets/EventSourcing.svg" alt="Wow-EventSourcing"/>
</p>

### Observability

<p align="center" style="text-align:center">
  <img src="./document/design/assets/OpenTelemetry.png" alt="Wow-Observability"/>
</p>

## Preconditions

- Understanding **Domain Driven Design**：《Implementing Domain-Driven Design》,《Domain-Driven Design: Tackling Complexity
  in the Heart of Software》
- Understanding **Command Query Responsibility Segregation**(CQRS)
- Understanding **EventSourcing**
- Understanding **Reactive Programming**

## Features

- [x] Aggregate Modeling
    - [x] Single Class
    - [x] Inheritance Pattern
    - [x] Aggregation Pattern
- [ ] Saga Modeling
    - [x] `StatelessSaga`
    - [ ] `StatefulSaga`
- [x] Test Suite
    - [x] Test Specification
    - [x] `AggregateVerifier`
    - [x] `StatelessSagaVerifier`
- [x] EventSourcing
    - EventStore
        - [x] MongoDB (Recommend)
        - [x] R2dbc
            - [x] Database Sharding
            - [x] Table Sharding
        - [x] Redis
    - Snapshot
        - [x] MongoDB
        - [x] R2dbc
            - [x] Database Sharding
            - [x] Table Sharding
        - [x] ElasticSearch
        - [x] Redis (Recommend)
        - [x] `KafkaSnapshotSink`
- [x] CommandBus
    - [x] `InMemoryCommandBus`
    - [x] `KafkaCommandBus` (Recommend)
    - [x] `RedisCommandBus`
    - [x] `LocalFirstCommandBus`
- [x] DomainEventBus
    - [x] `InMemoryDomainEventBus` 
    - [x] `KafkaDomainEventBus` (Recommend)
    - [x] `RedisDomainEventBus`
- [x] Spring Integration
    - [x] Spring Boot Auto Configuration
    - [x] Automatically register `CommandAggregate` to `RouterFunction`
- [x] Observability
    - [x] OpenTelemetry
- [x] OpenApi
- [x] `WowMetadata` Generator
    - [x] `wow-compiler`

## Example

[Example](./example)

## Aggregate Unit Test

### 80% test coverage is a breeze.

![Test Coverage](./document/example/example-domain-jococo.png)

> Given -> When -> Expect .

```kotlin
internal class OrderTest {

    companion object {
        val SHIPPING_ADDRESS = ShippingAddress("China", "ShangHai", "ShangHai", "HuangPu", "001")
    }

    private fun mockCreateOrder(): VerifiedStage<OrderState> {
        val tenantId = GlobalIdGenerator.generateAsString()
        val customerId = GlobalIdGenerator.generateAsString()

        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10
        )
        val orderItems = listOf(orderItem)
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return orderItems.toFlux().filter { it.productId == productId }.map { it.quantity }.last()
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return orderItems.toFlux().filter { it.productId == productId }.map { it.price }.last()
            }
        }
        return aggregateVerifier<Order, OrderState>(tenantId = tenantId)
            .inject(DefaultCreateOrderSpec(inventoryService, pricingService))
            .given()
            .`when`(CreateOrder(customerId, orderItems, SHIPPING_ADDRESS))
            .expectEventCount(1)
            .expectEventType(OrderCreated::class.java)
            .expectStateAggregate {
                assertThat(it.aggregateId.tenantId, equalTo(tenantId))
            }
            .expectState {
                assertThat(it.id, notNullValue())
                assertThat(it.customerId, equalTo(customerId))
                assertThat(it.address, equalTo(SHIPPING_ADDRESS))
                assertThat(it.items, equalTo(orderItems))
                assertThat(it.status, equalTo(OrderStatus.CREATED))
            }
            .verify()
    }

    /**
     * 创建订单
     */
    @Test
    fun createOrder() {
        mockCreateOrder()
    }

    /**
     * 创建订单-库存不足
     */
    @Test
    fun createOrderWhenInventoryShortage() {
        val customerId = GlobalIdGenerator.generateAsString()
        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10
        )
        val orderItems = listOf(orderItem)
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return orderItems.toFlux().filter { it.productId == productId }
                    /*
                     * 模拟库存不足
                     */
                    .map { it.quantity - 1 }.last()
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return orderItems.toFlux().filter { it.productId == productId }.map { it.price }.last()
            }
        }

        aggregateVerifier<Order, OrderState>()
            .inject(DefaultCreateOrderSpec(inventoryService, pricingService))
            .given()
            .`when`(CreateOrder(customerId, orderItems, SHIPPING_ADDRESS))
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
        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10
        )
        val orderItems = listOf(orderItem)
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return orderItems.toFlux().filter { it.productId == productId }.map { it.quantity }.last()
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return orderItems.toFlux().filter { it.productId == productId }
                    /*
                     * 模拟下单价格、商品定价不一致
                     */
                    .map { it.price.plus(BigDecimal.valueOf(1)) }.last()
            }
        }
        aggregateVerifier<Order, OrderState>()
            .inject(DefaultCreateOrderSpec(inventoryService, pricingService))
            .given()
            .`when`(CreateOrder(customerId, orderItems, SHIPPING_ADDRESS))
            /*
             * 期望：价格不一致异常.
             */
            .expectErrorType(PriceInconsistencyException::class.java).verify()
    }

    private fun mockPayOrder(): VerifiedStage<OrderState> {
        val verifiedStageAfterCreateOrder = mockCreateOrder()
        val previousState = verifiedStageAfterCreateOrder.stateRoot
        val payOrder = PayOrder(
            previousState.id,
            GlobalIdGenerator.generateAsString(),
            previousState.totalAmount
        )

        return verifiedStageAfterCreateOrder
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
     * 支付订单-超付
     */
    @Test
    fun payOrderWhenOverPay() {
        val verifiedStageAfterCreateOrder = mockCreateOrder()
        val previousState = verifiedStageAfterCreateOrder.stateRoot
        val payOrder = PayOrder(
            previousState.id,
            GlobalIdGenerator.generateAsString(),
            previousState.totalAmount.plus(
                BigDecimal.valueOf(1)
            )
        )
        verifiedStageAfterCreateOrder
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
            .expectEventStream {
                val itr = it.iterator()
                /*
                 * OrderPaid
                 */
                val orderPaid = itr.next().body as OrderPaid
                assertThat(orderPaid.paid, equalTo(true))
                /*
                 * OrderOverPaid
                 */
                val orderOverPaid = itr.next().body as OrderOverPaid
                assertThat(
                    orderOverPaid.overPay,
                    equalTo(payOrder.amount.minus(previousState.totalAmount))
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

    /**
     * 发货
     */
    @Test
    fun ship() {
        val verifiedStageAfterPayOrder = mockPayOrder()
        val shipOrder = ShipOrder(verifiedStageAfterPayOrder.stateRoot.id)
        verifiedStageAfterPayOrder
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

    @Test
    fun shipGivenUnpaid() {
        val verifiedStageAfterCreateOrder = mockCreateOrder()
        val shipOrder = ShipOrder(verifiedStageAfterCreateOrder.stateRoot.id)
        verifiedStageAfterCreateOrder.then().given()
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

    private fun mockDeleteOrder(): VerifiedStage<OrderState> {
        val verifiedStageAfterCreateOrder = mockCreateOrder()
        return verifiedStageAfterCreateOrder.then().given()
            .`when`(DeleteAggregate)
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
            .`when`(DeleteAggregate)
            .expectErrorType(IllegalAccessDeletedAggregateException::class.java)
            .expectError<IllegalAccessDeletedAggregateException> {
                assertThat(it.aggregateId, equalTo(verifiedStageAfterDelete.stateAggregate.aggregateId))
            }.expectStateAggregate {
                assertThat(it.deleted, equalTo(true))
            }
            .verify()
    }
}
```

## Design

### Modeling

| **Single Class**                                                                       | **Inheritance Pattern**                                                                     | **Aggregation Pattern**                                                                     |
|----------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|:--------------------------------------------------------------------------------------------|
| ![Single Class - Modeling](./document/design/assets/Modeling-Single-Class-Pattern.svg) | ![Inheritance Pattern- Modeling](./document/design/assets/Modeling-Inheritance-Pattern.svg) | ![Aggregation Pattern- Modeling](./document/design/assets/Modeling-Aggregation-Pattern.svg) |

### Load Aggregate

<p align="center" style="text-align:center">
  <img src="./document/design/assets/Load-Aggregate.svg" alt="Load Aggregate"/>
</p>

### Aggregate State Flow

<p align="center" style="text-align:center">
  <img src="./document/design/assets/Aggregate-State-Flow.svg" alt="Aggregate State Flow"/>
</p>

### Send Command

<p align="center" style="text-align:center">
  <img src="./document/design/assets/Send-Command.svg" alt="Send Command"/>
</p>

### Command And Event Flow

<p align="center" style="text-align:center">
  <img src="./document/design/assets/Command-Event-Flow.svg" alt="Command And Event Flow"/>
</p>

### Saga - OrderProcessManager (Demo)

<p align="center" style="text-align:center">
  <img src="./document/design/assets/Saga-Order.svg" alt="OrderProcessManager"/>
</p>
