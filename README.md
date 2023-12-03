# Wow

> [中文文档](https://github.com/Ahoo-Wang/Wow/blob/main/README.zh-CN.md)

A Modern Reactive CQRS Architecture Microservice development framework based on DDD and EventSourcing.

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://github.com/Ahoo-Wang/Wow/blob/mvp/LICENSE)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/Wow.svg)](https://github.com/Ahoo-Wang/Wow/releases)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/me.ahoo.wow/wow-core/badge.svg)](https://maven-badges.herokuapp.com/maven-central/me.ahoo.wow/wow-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/cfc724df22db4f9387525258c8a59609)](https://app.codacy.com/gh/Ahoo-Wang/Wow/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/Wow/branch/main/graph/badge.svg?token=uloJrLoQir)](https://codecov.io/gh/Ahoo-Wang/Wow)
[![Integration Test Status](https://github.com/Ahoo-Wang/Wow/actions/workflows/integration-test.yml/badge.svg)](https://github.com/Ahoo-Wang/Wow)
[![Awesome Kotlin Badge](https://kotlin.link/awesome-kotlin.svg)](https://github.com/KotlinBy/awesome-kotlin)

**Domain-Driven** | **Event-Driven** | **Test-Driven** | **Declarative-Design** | **Reactive Programming** | **Command
Query Responsibility Segregation** | **Event Sourcing**

## Architecture

<p align="center" style="text-align:center">
  <img src="./document/design/assets/Architecture.svg" alt="Wow-Architecture"/>
</p>

## Performance Test (Example)

- Test Code: [Example](./example)
- Test Case: Add To Shopping Cart / Create Order
- Command `WaitStrategy`: `SENT`、`PROCESSED`

### Deployment

- [Redis](deploy/example/perf/redis.yaml)
- [MongoDB](deploy/example/perf/mongo.yaml)
- [Kafka](deploy/example/perf/kafka.yaml)
- [Application-Config](deploy/example/perf/config/mongo_kafka_redis.yaml)
- [Application-Deployment](deploy/example/perf/deployment.yaml)

### Test Report

#### Add To Shopping Cart

- [Request](deploy/example/request/AddCartItem.http)
- [Detailed Report(PDF)-SENT](./document/example/perf/Example.Cart.Add@SENT.pdf)
- [Detailed Report(PDF)-PROCESSED](./document/example/perf/Example.Cart.Add@PROCESSED.pdf)

> `WaitStrategy`: `SENT`

![SENT](./document/example/perf/Example.Cart.Add@SENT.png)

> `WaitStrategy`: `PROCESSED`

![PROCESSED](./document/example/perf/Example.Cart.Add@PROCESSED.png) 

#### Create Order

- [Request](deploy/example/request/CreateOrder.http)
- [Detailed Report(PDF)-SENT](./document/example/perf/Example.Order.Create@SENT.pdf)
- [Detailed Report(PDF)-PROCESSED](./document/example/perf/Example.Order.Create@PROCESSED.pdf)

> `WaitStrategy`: `SENT`

![SENT](./document/example/perf/Example.Order.Create@SENT.png)

> `WaitStrategy`: `PROCESSED`

![PROCESSED](./document/example/perf/Example.Order.Create@PROCESSED.png)

## Event Sourcing

<p align="center" style="text-align:center">
  <img src="./document/design/assets/EventSourcing.svg" alt="Wow-EventSourcing"/>
</p>

## Observability

<p align="center" style="text-align:center">
  <img src="./document/design/assets/OpenTelemetry.png" alt="Wow-Observability"/>
</p>

## OpenAPI (Spring WebFlux Integration)

> Automatically register the `Command` routing processing function (`HandlerFunction`), and developers only need to
> write the domain model to complete the service development.

<p align="center" style="text-align:center">
  <img src="document/design/assets/OpenAPI-Swagger.png" alt="Wow-Spring-WebFlux-Integration"/>
</p>

## Test suite: 80%+ test coverage is very easy

> Given -> When -> Expect .

<p align="center" style="text-align:center">
  <img src="./document/design/assets/CI-Flow.png" alt="Wow-CI-Flow"/>
</p>

## Preconditions

- Understanding **Domain Driven Design**：《Implementing Domain-Driven Design》,《Domain-Driven Design: Tackling Complexity
  in the Heart of Software》
- Understanding **Command Query Responsibility Segregation**(CQRS)
- Understanding **EventSourcing**
- Understanding **Reactive Programming**

## Quick Start

Use WOW framework templates to quickly create projects.
Template Url：[Wow-Project-Template](https://github.com/Ahoo-Wang/wow-project-template)


## Features

- [x] Aggregate Modeling
    - [x] Single Class
    - [x] Inheritance Pattern
    - [x] Aggregation Pattern
- [x] Saga Modeling
    - [x] `StatelessSaga`
- [x] Test Suite
    - [x] The Technology Compatibility Kit
    - [x] `AggregateVerifier`
    - [x] `SagaVerifier`
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
- [x] Command `WaitStrategy`
    - [x] `SENT` : Send a completion signal after the command is sent
    - [x] `PROCESSED` : Send a completion signal when command processing is complete
    - [x] `SNAPSHOT` : Send a completion signal after the snapshot generation is complete
    - [x] `PROJECTED` : Send a completion signal when the event generated by the command has been projected
- [x] CommandBus
    - [x] `InMemoryCommandBus`
    - [x] `KafkaCommandBus` (Recommend)
    - [x] `RedisCommandBus`
    - [x] `LocalFirstCommandBus`
- [x] DomainEventBus
    - [x] `InMemoryDomainEventBus`
    - [x] `KafkaDomainEventBus` (Recommend)
    - [x] `RedisDomainEventBus`
    - [x] `LocalFirstDomainEventBus`
- [x] StateEventBus
    - [x] `InMemoryStateEventBus`
    - [x] `KafkaStateEventBus` (Recommend)
    - [x] `RedisStateEventBus`
    - [x] `LocalFirstStateEventBus`
- [x] Spring Integration
    - [x] Spring Boot Auto Configuration
    - [x] Automatically register `CommandAggregate` to `RouterFunction`
- [x] Observability
    - [x] OpenTelemetry
- [x] OpenAPI
- [x] `WowMetadata` Generator
    - [x] `wow-compiler`

### Order Service（Kotlin）

[Example-Order](./example)

### Transfer（JAVA）

[Example-Transfer](./example/transfer)

## Unit Test Suite

### 80%+ test coverage is very easy.

![Test Coverage](./document/example/example-domain-jococo.png)

> Given -> When -> Expect .

### Aggregate Unit Test (`AggregateVerifier`)

[Aggregate Test](./example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/order/OrderTest.kt)

```kotlin
internal class OrderTest {

    private fun mockCreateOrder(): VerifiedStage<OrderState> {
        val tenantId = GlobalIdGenerator.generateAsString()
        val customerId = GlobalIdGenerator.generateAsString()

        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10,
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
        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10,
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
        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10,
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
}
```

### Saga Unit Test (`SagaVerifier`)

[Saga Test](./example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart/CartSagaTest.kt)

```kotlin
class CartSagaTest {

    @Test
    fun onOrderCreated() {
        val orderItem = OrderItem(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
            BigDecimal.valueOf(10),
            10,
        )
        sagaVerifier<CartSaga>()
            .`when`(
                mockk<OrderCreated> {
                    every {
                        customerId
                    } returns "customerId"
                    every {
                        items
                    } returns listOf(orderItem)
                    every {
                        fromCart
                    } returns true
                },
            )
            .expectCommandBody<RemoveCartItem> {
                assertThat(it.id, equalTo("customerId"))
                assertThat(it.productIds, hasSize(1))
                assertThat(it.productIds.first(), equalTo(orderItem.productId))
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

