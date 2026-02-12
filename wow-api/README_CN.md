# Wow API

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-api)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-api)

Wow 框架的核心 API 定义模块，为构建基于领域驱动设计 (DDD)、命令查询职责分离 (CQRS) 和事件溯源模式的应用程序提供基本的接口、注解和类型。

## 简介

Wow API 是 [Wow 框架](https://github.com/Ahoo-Wang/Wow) 的基础模块，Wow 是一个基于 DDD 和事件溯源的现代响应式微服务开发框架。该模块定义了核心抽象和契约，支持：

- **领域驱动设计**：聚合根、领域事件、值对象和实体
- **CQRS 架构**：具有清晰边界的分离命令和查询模型
- **事件溯源**：用于状态重建和审计跟踪的不可变事件流
- **响应式编程**：非阻塞异步处理管道
- **类型安全**：利用 Kotlin 类型系统的强类型 API

## 安装

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-api:6.5.2")
}
```

### Gradle (Groovy DSL)

```gradle
dependencies {
    implementation 'me.ahoo.wow:wow-api:6.5.2'
}
```

### Maven

```xml
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-api</artifactId>
    <version>6.5.2</version>
</dependency>
```

## 使用方法

### 定义命令和事件

命令和事件是带有验证和路由注解的简单数据类：

#### 带有验证和路由的命令

```kotlin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.annotation.Summary

@Order(1)
@AllowCreate
@CommandRoute(method = CommandRoute.Method.POST)
@Summary("加入购物车")
data class AddCartItem(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int = 1
)

@Order(2)
@Summary("变更购买数量")
@CommandRoute(appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class ChangeQuantity(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int
)

@Order(3)
@Summary("删除商品")
data class RemoveCartItem(
    @field:NotEmpty
    val productIds: Set<String>
)
```

#### 领域事件

```kotlin
@Summary("商品已加入购物车")
data class CartItemAdded(
    val added: CartItem
)

data class CartQuantityChanged(
    val changed: CartItem
)

data class CartItemRemoved(
    val productIds: Set<String>
)
```

#### 值对象和实体

```kotlin
import me.ahoo.wow.api.annotation.ValueObject
import me.ahoo.wow.api.annotation.EntityObject

@ValueObject
data class CartItem(
    val productId: String,
    val quantity: Int = 1
)

@EntityObject
data class OrderItem(
    override val id: String,
    val productId: String,
    val price: BigDecimal,
    val quantity: Int
) : Identifier {
    val totalPrice: BigDecimal
        get() = price.multiply(quantity.toBigDecimal())
}
```

### 限界上下文配置

定义限界上下文和聚合：

```kotlin
import me.ahoo.wow.api.annotation.BoundedContext

@BoundedContext(
    name = "example-service",
    alias = "example",
    description = "示例服务上下文",
    aggregates = [
        BoundedContext.Aggregate("order", packageScopes = [CreateOrder::class]),
        BoundedContext.Aggregate(
            "cart",
            tenantId = TenantId.DEFAULT_TENANT_ID,
            packageScopes = [AddCartItem::class]
        ),
    ],
)
object ExampleService {
    const val SERVICE_NAME = "example-service"
    const val ORDER_AGGREGATE_NAME = "order"
    const val CART_AGGREGATE_NAME = "cart"
}
```

## API 参考

### 核心注解

- `@AggregateRoot` - 标记类为 DDD 中的聚合根
- `@AggregateId` - 标记字段为聚合标识符
- `@Event` - 标记类为领域事件
- `@OnCommand` - 标记方法为命令处理器
- `@OnSourcing` - 标记方法为事件溯源处理器
- `@StatelessSaga` - 标记类为无状态 Saga 编排器
- `@OnEvent` - 标记方法为事件处理器（用于 Saga）
- `@Retry` - 配置事件处理的 retry 行为
- `@BoundedContext` - 定义限界上下文边界
- `@CommandRoute` - 为命令配置 REST API 路由
- `@AllowCreate` - 允许命令创建新聚合
- `@CreateAggregate` - 标记命令为创建新聚合
- `@VoidCommand` - 标记命令为即发即弃（无响应）
- `@Order` - 定义命令/事件的执行顺序
- `@Summary` - 提供人类可读的描述
- `@ValueObject` - 标记类为值对象
- `@EntityObject` - 标记类为聚合内的实体

### 关键接口

#### CommandMessage<T>
表示要对聚合执行的命令。

**关键属性：**
- `aggregateId: AggregateId` - 目标聚合标识符
- `aggregateVersion: Int?` - 用于乐观锁的期望版本
- `isCreate: Boolean` - 是否创建新聚合
- `allowCreate: Boolean` - 是否允许创建
- `isVoid: Boolean` - 是否期望响应

#### DomainEvent<T>
表示关于业务发生事件的不可变事实。

**关键属性：**
- `aggregateId: AggregateId` - 源聚合标识符
- `sequence: Int` - 事件序列号
- `revision: String` - 事件模式版本
- `isLast: Boolean` - 是否为最后一个事件

#### AggregateId
在限界上下文中标识聚合实例。

**属性：**
- `contextName: String` - 限界上下文名称
- `aggregateName: String` - 聚合类型名称
- `id: String` - 实例标识符

### Saga 编排

Saga 使用事件驱动的编排来协调分布式事务：

```kotlin
@StatelessSaga
class TransferSaga {

    fun onEvent(prepared: Prepared, aggregateId: AggregateId): Entry {
        return Entry(prepared.to(), aggregateId.id, prepared.amount())
    }

    fun onEvent(amountEntered: AmountEntered): Confirm {
        return Confirm(amountEntered.sourceId(), amountEntered.amount())
    }

    fun onEvent(entryFailed: EntryFailed): UnlockAmount {
        return UnlockAmount(entryFailed.sourceId(), entryFailed.amount())
    }
}
```

### 查询 API

该模块提供全面的查询功能：

```kotlin
// 单个实体查询
val query = SingleQuery(
    aggregateId = AggregateId("order", "order-123")
)

// 分页列表查询
val pagedQuery = PagedQuery(
    condition = Condition("status", Operator.EQ, "PENDING"),
    sort = listOf(Sort("createdAt", Direction.DESC)),
    pagination = Pagination(page = 1, size = 20)
)

// 动态文档查询
val dynamicQuery = DynamicDocument(
    condition = Condition("customerId", Operator.EQ, customerId),
    projection = listOf("orderId", "totalAmount", "status")
)
```

## 建模模式

### 聚合模式（推荐）

将命令处理与状态管理分离，以实现更好的关注点分离：

```kotlin
// 命令聚合根 - 处理业务逻辑
@AggregateRoot
class Cart(private val state: CartState) {

    @OnCommand
    fun onCommand(command: AddCartItem): CartItemAdded {
        require(state.items.size < MAX_CART_ITEM_SIZE) {
            "购物车最多只能添加[$MAX_CART_ITEM_SIZE]个商品."
        }
        // 业务逻辑在这里
        return CartItemAdded(added = CartItem(command.productId, command.quantity))
    }
}

// 状态聚合根 - 管理状态数据
class CartState(val id: String) {
    var items: List<CartItem> = listOf()
        private set

    @OnSourcing
    fun onCartItemAdded(event: CartItemAdded) {
        items = items + event.added
    }
}
```

### 单类模式

将所有内容放在一个类中（更简单但不那么严格）：

```kotlin
@AggregateRoot
class Order(@AggregateId val orderId: String) {

    private var status: OrderStatus = OrderStatus.PENDING
    private val items: MutableList<OrderItem> = mutableListOf()

    @OnCommand
    fun create(command: CreateOrder): OrderCreated {
        return OrderCreated(orderId, command.items, command.customerId)
    }

    @OnSourcing
    fun onCreated(event: OrderCreated) {
        items.addAll(event.items)
        status = OrderStatus.CREATED
    }
}
```

## 示例

### 购物车 API（真实示例）

基于实际的示例实现：

```kotlin
// 带有验证和路由的命令
@Order(1)
@AllowCreate
@CommandRoute(method = CommandRoute.Method.POST)
@Summary("加入购物车")
data class AddCartItem(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int = 1
)

@Order(2)
@Summary("变更购买数量")
@CommandRoute(appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class ChangeQuantity(
    @field:NotBlank
    val productId: String,
    @field:Positive
    val quantity: Int
)

@Order(3)
@Summary("删除商品")
data class RemoveCartItem(
    @field:NotEmpty
    val productIds: Set<String>
)

// 事件
@Summary("商品已加入购物车")
data class CartItemAdded(val added: CartItem)

data class CartQuantityChanged(val changed: CartItem)

data class CartItemRemoved(val productIds: Set<String>)

// 值对象
@ValueObject
data class CartItem(
    val productId: String,
    val quantity: Int = 1
)

// 用于查询的空命令
@VoidCommand
class ViewCart
```

### 订单管理 API（真实示例）

```kotlin
@Summary("下单")
@CommandRoute(action = "")
@CreateAggregate
data class CreateOrder(
    @field:Size(min = 1)
    val items: List<Item>,
    @field:NotNull @field:Valid
    val address: ShippingAddress,
    val fromCart: Boolean
) : CommandValidator {

    override fun validate() {
        require(address.country == "China") {
            "Only support China shipping address."
        }
    }

    data class Item(
        @field:NotEmpty
        override val productId: String,
        @field:Positive
        override val price: BigDecimal,
        @field:Positive
        override val quantity: Int
    ) : CreateOrderItem
}

@CommandRoute("pay", method = CommandRoute.Method.POST, appendOwnerPath = CommandRoute.AppendPath.NEVER)
data class PayOrder(
    @field:NotBlank
    val paymentId: String,
    @field:Positive
    val amount: BigDecimal
)

// 事件
data class OrderCreated(
    val orderId: String,
    val items: List<OrderItem>,
    val address: ShippingAddress,
    val fromCart: Boolean
)

data class OrderPaid(val amount: BigDecimal, val paid: Boolean)
data class OrderOverPaid(val paymentId: String, val overPay: BigDecimal)

// 错误处理
data class OrderPayDuplicated(val paymentId: String, override val errorMsg: String) : ErrorInfo {
    override val errorCode: String get() = "OrderPayDuplicated"
}

// 值对象和实体
@ValueObject
data class ShippingAddress(
    @field:NotBlank
    val country: String,
    @field:NotBlank
    val province: String,
    val city: String,
    val district: String,
    val detail: String
)

@EntityObject
data class OrderItem(
    override val id: String,
    override val productId: String,
    override val price: BigDecimal,
    override val quantity: Int
) : Identifier, CreateOrderItem {

    val totalPrice: BigDecimal
        get() = price.multiply(quantity.toBigDecimal())
}
```

### 聚合实现

```kotlin
@AggregateRoot
class Cart(private val state: CartState) {

    @OnCommand
    fun onCommand(command: AddCartItem): CartItemAdded {
        // 业务逻辑验证
        return CartItemAdded(CartItem(command.productId, command.quantity))
    }

    @OnCommand
    fun onCommand(command: ChangeQuantity): CartQuantityChanged {
        // 更新现有商品数量
        val updated = state.items.find { it.productId == command.productId }
            ?.copy(quantity = command.quantity)
            ?: throw IllegalArgumentException("商品未找到")

        return CartQuantityChanged(updated)
    }
}

class CartState(val id: String) {
    var items: List<CartItem> = listOf()
        private set

    @OnSourcing
    fun onCartItemAdded(event: CartItemAdded) {
        items = items + event.added
    }

    @OnSourcing
    fun onCartQuantityChanged(event: CartQuantityChanged) {
        items = items.map {
            if (it.productId == event.changed.productId) event.changed else it
        }
    }
}
```

## 贡献

我们欢迎对 Wow API 模块的贡献！请查看主 [Wow 仓库](https://github.com/Ahoo-Wang/Wow) 了解贡献指南。

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
   ./gradlew :wow-api:test
   ```

### 代码风格

本项目遵循 Kotlin 编码规范，并使用 Detekt 进行静态分析。使用以下命令格式化代码：

```bash
./gradlew detekt --auto-correct
```

## 许可证

Wow API 采用 Apache License 2.0 许可证。详见 [LICENSE](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)。