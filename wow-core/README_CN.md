# Wow Core

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-core)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-core)

Wow 框架的核心实现模块，为 CQRS 和事件溯源应用程序提供必要的运行时基础设施，包括命令处理、事件处理、事件溯源、Saga 编排和投影。

## 简介

Wow Core 是 [Wow 框架](https://github.com/Ahoo-Wang/Wow) 的基础运行时模块，实现了构建领域驱动设计 (DDD) 应用程序所需的 CQRS 和事件溯源的核心模式和基础设施。

该模块提供：

- **命令处理**：带有验证、幂等性和等待策略的命令网关
- **事件处理**：领域事件总线、分发和处理管道
- **事件溯源**：事件存储实现和状态重建
- **Saga 编排**：使用事件驱动工作流的分布式事务协调
- **投影**：响应领域事件的查询模型更新
- **序列化**：命令、事件和聚合状态的 JSON 序列化
- **异常处理**：全面的错误处理和恢复机制
- **消息传播**：头部传播、跟踪和跨服务通信

## 安装

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-core:6.5.2")
}
```

### Gradle (Groovy DSL)

```gradle
dependencies {
    implementation 'me.ahoo.wow:wow-core:6.5.2'
}
```

### Maven

```xml
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-core</artifactId>
    <version>6.5.2</version>
</dependency>
```

## 使用方法

### 命令处理

`CommandGateway` 提供带有验证、幂等性和灵活等待策略的全面命令处理：

```kotlin
// 发送带有验证和幂等性检查的命令
val addCartItem = AddCartItem(productId = "product-123", quantity = 2)
val command = addCartItem.toCommandMessage(ownerId = "customer-456")

gateway.send(command)
    .doOnSuccess { println("命令发送成功") }
    .subscribe()

// 发送并等待完成，使用不同的策略
gateway.sendAndWait(command, WaitStrategy.PROCESSED)
    .doOnNext { result ->
        if (result.succeeded) {
            println("购物车商品添加成功: ${result.commandId}")
        }
    }
    .subscribe()

// 实时流式返回命令结果
gateway.sendAndWaitStream(command, WaitStrategy.PROCESSED)
    .doOnNext { result ->
        println("命令阶段: ${result.stage} - ${result.succeeded}")
    }
    .subscribe()
```

### 事件处理

领域事件通过事件总线发布并由处理器处理：

```kotlin
// 事件由聚合在命令处理后自动发布
// 框架内部处理事件发布

// 使用投影处理事件
@ProjectionProcessor
class OrderProjector {

    @OnEvent
    fun onOrderCreated(event: OrderCreated): Mono<Void> {
        return orderRepository.save(
            OrderSummary(
                id = event.aggregateId.id,
                totalAmount = event.items.sumOf { it.totalPrice },
                status = OrderStatus.CREATED,
                createdAt = event.createTime
            )
        ).then()
    }

    @OnEvent
    fun onOrderPaid(event: OrderPaid): Mono<Void> {
        return orderRepository.findById(event.aggregateId.id)
            .flatMap { summary ->
                val updated = summary.copy(
                    status = OrderStatus.PAID,
                    paidAt = event.createTime
                )
                orderRepository.save(updated)
            }
            .then()
    }
}
```

### 事件溯源

从事件流加载和重建聚合状态：

```kotlin
// 加载事件流以进行状态重建
eventStore.load(aggregateId, headVersion = 1, tailVersion = Int.MAX_VALUE)
    .collectList()
    .map { eventStreams ->
        // 从事件重建聚合状态
        val state = OrderState(aggregateId.id)
        eventStreams.forEach { stream ->
            stream.events.forEach { event ->
                when (event) {
                    is OrderCreated -> {
                        state.items.addAll(event.items)
                        state.address = event.address
                        state.status = OrderStatus.CREATED
                    }
                    is OrderPaid -> {
                        state.status = OrderStatus.PAID
                        state.paidAmount = event.amount
                    }
                    is OrderShipped -> {
                        state.status = OrderStatus.SHIPPED
                    }
                    // ... 处理其他事件
                }
            }
        }
        state
    }
    .subscribe()

// 按时间范围加载事件用于审计
eventStore.load(aggregateId, headEventTime = startTime, tailEventTime = endTime)
    .flatMap { stream -> Flux.fromIterable(stream.events) }
    .collectList()
    .subscribe { events -> auditLog.append(events) }
```

### Saga 编排

使用事件驱动的 Saga 协调分布式事务：

```kotlin
@StatelessSaga
class CartSaga {

    /**
     * 下单后删除购物车相应商品
     */
    @Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
    @OnEvent
    fun onOrderCreated(event: DomainEvent<OrderCreated>): CommandBuilder? {
        val orderCreated = event.body
        if (!orderCreated.fromCart) {
            return null
        }
        // 构建删除购物车商品的命令
        return RemoveCartItem(
            productIds = orderCreated.items.map { it.productId }.toSet(),
        ).commandBuilder()
            .aggregateId(event.ownerId) // 购物车聚合 ID
    }
}
```

## API 参考

### 核心接口

#### CommandGateway
使用验证和等待策略发送命令的中央接口。

**关键方法：**
- `send(command: CommandMessage)` - 异步发送命令
- `sendAndWait(command, waitStrategy)` - 发送并等待完成
- `sendAndWaitStream(command, waitStrategy)` - 流式返回命令结果

#### DomainEventBus
发布和订阅领域事件流的 message bus。

**实现：**
- `LocalDomainEventBus` - 进程内事件处理
- `DistributedDomainEventBus` - 跨服务事件分发
- `LocalFirstDomainEventBus` - 本地/分布式混合方法

#### EventStore
具有版本支持的领域事件流的持久存储。

**关键方法：**
- `append(eventStream)` - 存储新的事件流
- `load(aggregateId, headVersion, tailVersion)` - 按版本范围加载事件流
- `load(aggregateId, headEventTime, tailEventTime)` - 按时间范围加载

#### StatelessSagaHandler
处理领域事件以协调分布式事务。

#### ProjectionHandler
响应领域事件更新查询模型。

### 关键组件

#### 命令处理
- **命令验证**：Jakarta 验证集成
- **幂等性检查**：防止重复命令处理
- **等待策略**：`SENT`、`PROCESSED`、`PROJECTED` 用于不同的一致性级别

#### 事件处理
- **事件分发**：按聚合的有序事件处理
- **事件过滤**：基于函数的事件路由
- **错误处理**：可配置的错误恢复策略

#### 序列化
- **消息序列化**：所有消息类型的 JSON 序列化
- **状态序列化**：聚合状态持久化
- **事件流序列化**：高效的事件存储格式

#### 异常处理
- **错误转换**：标准化的错误处理
- **可恢复异常**：自动重试机制
- **错误传播**：跨服务的一致错误报告

## 示例

### 完整的命令处理与 REST 控制器

```kotlin
@RestController
class CartController(
    private val commandGateway: CommandGateway,
    private val cartQueryClient: CartQueryClient
) {

    @PostMapping("/cart/{userId}/add-cart-item")
    fun addCartItem(@PathVariable userId: String): Flux<CommandResult> {
        val addCartItem = AddCartItem(
            productId = "product-123",
            quantity = 2
        )
        val command = addCartItem.toCommandMessage(ownerId = userId)

        // 实时流式返回命令处理结果
        return commandGateway.sendAndWaitStream(
            command,
            waitStrategy = WaitingForStage.snapshot(command.commandId)
        )
    }

    @GetMapping("/cart/me")
    fun getCart(): Mono<CartData> {
        return singleQuery {
            // 查询当前用户的购物车
        }.queryState(cartQueryClient)
    }
}
```

### 事件溯源聚合仓库

```kotlin
class EventSourcingOrderRepository(
    private val eventStore: EventStore,
    private val snapshotRepository: SnapshotRepository
) : OrderRepository {

    override fun load(orderId: String): Mono<OrderState> {
        val aggregateId = AggregateId("order", orderId)

        return snapshotRepository.load(aggregateId)
            .flatMap { snapshot ->
                // 加载快照版本之后的事件
                eventStore.load(aggregateId, snapshot.version + 1)
                    .collectList()
                    .map { eventStreams ->
                        val state = snapshot.state as OrderState
                        eventStreams.forEach { stream ->
                            stream.events.forEach { event ->
                                state.apply(event)
                            }
                        }
                        state
                    }
            }
            .switchIfEmpty(
                // 没有快照，从头开始加载所有事件
                eventStore.load(aggregateId, headVersion = 1)
                    .collectList()
                    .map { eventStreams ->
                        val state = OrderState(orderId)
                        eventStreams.forEach { stream ->
                            stream.events.forEach { event ->
                                state.apply(event)
                            }
                        }
                        state
                    }
            )
    }

    private fun OrderState.apply(event: DomainEvent<*>): OrderState {
        return when (event) {
            is OrderCreated -> {
                this.items.addAll(event.items)
                this.address = event.address
                this.status = OrderStatus.CREATED
                this
            }
            is OrderPaid -> {
                this.status = OrderStatus.PAID
                this.paidAmount += event.amount
                this
            }
            is OrderShipped -> {
                this.status = OrderStatus.SHIPPED
                this
            }
            is OrderReceived -> {
                this.status = OrderStatus.RECEIVED
                this
            }
            else -> this
        }
    }
}
```

### 投影实现

```kotlin
@ProjectionProcessor
class OrderProjector(
    private val orderRepository: OrderRepository
) {

    @OnEvent
    fun onOrderCreated(event: OrderCreated): Mono<Void> {
        // 记录事件用于监控
        log.info("订单创建: ${event.aggregateId.id}")

        // 异步更新读模型
        return orderRepository.saveOrderSummary(
            OrderSummary(
                id = event.aggregateId.id,
                items = event.items,
                address = event.address,
                totalAmount = event.items.sumOf { it.totalPrice },
                status = OrderStatus.CREATED,
                createdAt = event.createTime
            )
        ).then()
    }

    @OnEvent
    fun onOrderPaid(event: OrderPaid): Mono<Void> {
        log.debug("订单支付: ${event.aggregateId.id}")

        return orderRepository.updateOrderStatus(
            event.aggregateId.id,
            OrderStatus.PAID,
            paidAt = event.createTime
        ).then()
    }

    @OnEvent
    fun onOrderShipped(event: OrderShipped): Mono<Void> {
        return orderRepository.updateOrderStatus(
            event.aggregateId.id,
            OrderStatus.SHIPPED,
            shippedAt = event.createTime
        ).then()
    }

    // 处理状态事件以进行额外处理
    @OnEvent
    fun onStateEvent(event: OrderCreated, state: OrderState): Mono<Void> {
        // 同时访问事件和当前状态以进行复杂逻辑
        log.info("创建后的订单状态: ${state.toJsonString()}")
        return Mono.empty()
    }
}
```

## 配置

### 命令网关配置

```yaml
wow:
  command:
    bus:
      type: kafka  # in_memory, kafka, redis 等
      local-first:
        enabled: true  # 启用本地优先优化
    idempotency:
      enabled: true  # 启用命令幂等性检查
      bloom-filter:
        expected-insertions: 1000000
        ttl: PT60S  # 幂等性记录的生存时间
        fpp: 0.00001  # 假阳性概率
```

### 事件存储配置

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo  # mongo, redis, r2dbc, in_memory, delay
    snapshot:
      enabled: true  # 启用快照优化
      strategy: all  # all, version_offset
      storage: mongo  # mongo, redis, r2dbc, elasticsearch, in_memory, delay
      version-offset: 5  # 快照的版本偏移量
    state:
      bus:
        type: kafka  # in_memory, kafka, redis 等
        local-first:
          enabled: true
```

### Saga 配置

Saga 编排在使用 `@StatelessSaga` 注解时会自动配置。通常不需要额外配置，但可以通过过滤器链自定义错误处理行为。

## 贡献

我们欢迎对 Wow Core 模块的贡献！请查看主 [Wow 仓库](https://github.com/Ahoo-Wang/Wow) 了解贡献指南。

### 开发环境设置

1. 克隆仓库：
   ```bash
   git clone https://github.com/Ahoo-Wang/Wow.git
   cd Wow
   ```

2. 构建项目：
   ```bash
   ./gradlew build
   ```

3. 运行测试：
   ```bash
   ./gradlew :wow-core:test
   ```

### 代码风格

本项目遵循 Kotlin 编码规范，并使用 Detekt 进行静态分析。使用以下命令格式化代码：

```bash
./gradlew detekt --auto-correct
```

## 许可证

Wow Core 采用 Apache License 2.0 许可证。详见 [LICENSE](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)。