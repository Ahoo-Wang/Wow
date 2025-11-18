# wow-test

[![Build Status](https://github.com/Ahoo-Wang/Wow/workflows/CI/badge.svg)](https://github.com/Ahoo-Wang/Wow/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-test)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-test)
[![Version](https://img.shields.io/badge/version-6.5.2-blue.svg)](https://github.com/Ahoo-Wang/Wow/releases)

WOW 框架的综合测试库，提供基于 Given/When/Expect 模式的领域聚合和无状态 Saga 测试的流畅 DSL。

## 简介

`wow-test` 库简化了 WOW 框架中领域驱动设计组件的测试。它提供：

- **聚合测试**：测试命令处理、事件生成和状态变更
- **Saga 测试**：测试无状态 Saga 行为和命令生成
- **流畅 DSL**：可读的、可链式的测试场景 API
- **隔离性**：内存事件存储和命令总线用于隔离测试
- **依赖注入**：支持模拟服务和依赖项

## 安装

在 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    testImplementation("me.ahoo.wow:wow-test:${wow.version}")
}
```

或 Maven：

```xml
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-test</artifactId>
    <version>${wow.version}</version>
    <scope>test</scope>
</dependency>
```

## 使用

### 聚合测试

使用 `AggregateSpec` 进行全面的聚合测试：

```kotlin
import me.ahoo.wow.test.AggregateSpec

class CartSpec : AggregateSpec<Cart, CartState>({
    on {
        val ownerId = generateGlobalId()
        val addCartItem = AddCartItem(productId = "productId", quantity = 1)
        givenOwnerId(ownerId)
        whenCommand(addCartItem) {
            expectNoError()
            expectEventType(CartItemAdded::class)
            expectState { items.assert().hasSize(1) }
            expectStateAggregate { ownerId.assert().isEqualTo(ownerId) }

            fork("Remove Item") {
                val removeCartItem = RemoveCartItem(productIds = setOf(addCartItem.productId))
                whenCommand(removeCartItem) {
                    expectEventType(CartItemRemoved::class)
                    expectState { items.assert().isEmpty() }
                }
            }

            fork("Delete Aggregate", verifyError = false) {
                whenCommand(DefaultDeleteAggregate) {
                    expectEventType(DefaultAggregateDeleted::class)
                    expectStateAggregate { deleted.assert().isTrue() }

                    fork("Access Deleted Aggregate") {
                        whenCommand(addCartItem) {
                            expectErrorType(IllegalAccessDeletedAggregateException::class)
                        }
                    }
                }
            }
        }
    }
})
```

### Saga 测试

使用 `SagaSpec` 测试无状态 Saga 行为：

```kotlin
import me.ahoo.wow.test.SagaSpec

class CartSagaSpec : SagaSpec<CartSaga>({
    on {
        val orderItem = OrderItem(id = generateGlobalId(), productId = generateGlobalId(), price = BigDecimal.TEN, quantity = 10)
        whenEvent(
            event = mockk<OrderCreated> {
                every { items } returns listOf(orderItem)
                every { fromCart } returns true
            },
            ownerId = generateGlobalId()
        ) {
            expectCommandType(RemoveCartItem::class)
            expectCommand<RemoveCartItem> {
                body.productIds.assert().hasSize(1)
                body.productIds.assert().first().isEqualTo(orderItem.productId)
            }
        }
    }
})
```

### 高级场景

对于包含服务注入和错误处理的复杂工作流：

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        val ownerId = generateGlobalId()
        val orderItem = CreateOrder.Item(productId = generateGlobalId(), price = BigDecimal.TEN, quantity = 10)

        givenOwnerId(ownerId)

        // 注入模拟服务
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String) = orderItem.quantity.toMono()
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String) = orderItem.price.toMono()
        }

        inject { register(DefaultCreateOrderSpec(inventoryService, pricingService)) }

        whenCommand(CreateOrder(listOf(orderItem), shippingAddress, false)) {
            expectNoError()
            expectEventType(OrderCreated::class)
            expectState { status.assert().isEqualTo(OrderStatus.CREATED) }

            fork("Pay Order") {
                val payOrder = PayOrder(generateGlobalId(), orderItem.price * BigDecimal(orderItem.quantity))
                whenCommand(payOrder) {
                    expectEventType(OrderPaid::class)
                    expectState { status.assert().isEqualTo(OrderStatus.PAID) }

                    fork("Ship Order") {
                        whenCommand(ShipOrder(stateAggregate.aggregateId.id)) {
                            expectEventType(OrderShipped::class)
                            expectState { status.assert().isEqualTo(OrderStatus.SHIPPED) }
                        }
                    }

                    fork("Duplicate Payment") {
                        whenCommand(PayOrder(generateGlobalId(), orderItem.price * BigDecimal(orderItem.quantity))) {
                            expectErrorType(DomainEventException::class)
                            expectEventType(OrderPayDuplicated::class)
                        }
                    }
                }
            }

            fork("Invalid Operation") {
                whenCommand(ShipOrder(stateAggregate.aggregateId.id)) {
                    expectErrorType(IllegalStateException::class)
                    expectState { status.assert().isEqualTo(OrderStatus.CREATED) }
                }
            }
        }
    }
})
```

### 引用点和跨场景分支

使用 `ref()` 标记验证点，并使用 `fork(ref, ...)` 从它们分支到不同的测试场景：

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        val orderId = generateGlobalId()
        val createOrder = CreateOrder(/*...*/)

        whenCommand(createOrder) {
            expectEventType(OrderCreated::class)
            ref("order-created")  // 标记此验证点
            expectState { status.assert().isEqualTo(OrderStatus.CREATED) }
        }
    }

    // 在单独的场景中从标记点分支
    fork("order-created", "Pay Order") {
        val payOrder = PayOrder(/*...*/)
        whenCommand(payOrder) {
            expectEventType(OrderPaid::class)
            expectState { status.assert().isEqualTo(OrderStatus.PAID) }
        }
    }

    fork("order-created", "Cancel Order") {
        val cancelOrder = CancelOrder(/*...*/)
        whenCommand(cancelOrder) {
            expectEventType(OrderCancelled::class)
            expectState { status.assert().isEqualTo(OrderStatus.CANCELLED) }
        }
    }
})
```

## API 参考

### AggregateSpec

使用 Given/When/Expect 模式测试聚合的规范类：

- `AggregateSpec<C, S>(block: AggregateDsl<S>.() -> Unit)`：接受 DSL 块的构造函数
- `on(block: GivenDsl<S>.() -> Unit)`：定义测试场景
- `fork(ref: String, name: String = "", verifyError: Boolean = false, block: ForkedVerifiedStageDsl<S>.() -> Unit)`：从之前引用的验证点创建分支测试场景

### SagaSpec

测试无状态 Saga 的规范类：

- `SagaSpec<T>(block: StatelessSagaDsl<T>.() -> Unit)`：接受 DSL 块的构造函数
- `on(block: WhenDsl<T>.() -> Unit)`：定义测试场景

### DSL 接口

#### AggregateDsl
- `on(block: GivenDsl<S>.() -> Unit)`：定义完整的测试场景
- `fork(ref: String, name: String = "", verifyError: Boolean = false, block: ForkedVerifiedStageDsl<S>.() -> Unit)`：从之前引用的 ExpectStage 创建分支测试场景

#### GivenDsl
- `inject(block: ServiceProvider.() -> Unit)`：注入服务或依赖项
- `givenOwnerId(ownerId: String)`：为聚合设置所有者 ID
- `givenEvent(event: Any, block: WhenDsl<S>.() -> Unit)`：使用领域事件初始化
- `givenEvent(events: Array<out Any>, block: WhenDsl<S>.() -> Unit)`：使用多个事件初始化
- `givenState(state: S, version: Int, block: WhenDsl<S>.() -> Unit)`：使用直接状态初始化

#### WhenDsl
- `whenCommand(command: Any, header: Header, ownerId: String, block: ExpectDsl<S>.() -> Unit)`：执行命令

#### ExpectDsl
- `expect(expected: ExpectedResult<S>.() -> Unit)`：定义对完整测试结果的期望
- `expectNoError()`：断言未发生错误
- `expectError()`：断言命令处理过程中发生了错误
- `expectError(expected: E.() -> Unit)`：定义对特定发生的错误的期望
- `expectErrorType(errorType: KClass<out Throwable>)`：断言特定错误类型
- `expectEventType(eventType: KClass<out Any>)`：断言生成的事件类型
- `expectEvent(expected: DomainEvent<E>.() -> Unit)`：定义对特定领域事件的期望
- `expectEventBody(expected: E.() -> Unit)`：定义对领域事件主体内容的期望
- `expectEventCount(expected: Int)`：定义对生成领域事件数量的期望
- `expectEventStream(expected: DomainEventStream.() -> Unit)`：定义对完整领域事件流的期望
- `expectEventIterator(expected: EventIterator.() -> Unit)`：定义对遍历领域事件的期望
- `expectState(block: S.() -> Unit)`：验证聚合状态
- `expectState(expected: Consumer<S>)`：使用 Consumer 定义对聚合状态的期望（Java）
- `expectStateAggregate(block: StateAggregate<S>.() -> Unit)`：验证聚合元数据
- `ref(ref: String)`：标记当前验证点以供后续分支使用
- `fork(name: String = "", verifyError: Boolean = false, block: ForkedVerifiedStageDsl<S>.() -> Unit)`：从当前验证状态创建分支测试场景

##### Fork 函数使用场景

`fork` 函数通过从验证状态创建独立测试分支来测试复杂工作流和边界情况：

- **顺序操作**：测试多步骤流程，如订单创建 → 支付 → 发货
- **错误场景**：验证在无效状态下尝试操作时的行为
- **替代路径**：从同一起点测试不同的命令序列
- **聚合生命周期**：测试删除、恢复和删除后的行为
- **业务规则**：验证跨状态转换的约束和业务逻辑

**引用点与 ref()：**
`ref()` 方法允许标记特定的验证点以供后续分支。使用 `AggregateDsl.fork(ref, ...)` 从任何之前标记的点创建分支，实现跨不同 `on` 块的复杂测试流程。

**最佳实践：**
- 为 fork 使用描述性名称以阐明测试意图
- 使用 `ref()` 标记重要的验证点以进行跨场景分支
- 避免深度嵌套（超过 3 层）- 使用 `ref()` 和 `fork(ref, ...)` 进行复杂分支
- 对相关操作使用 fork，对不相关场景使用单独的 `on` 块

#### StatelessSagaDsl
- `on(block: WhenDsl<T>.() -> Unit)`：定义 Saga 测试场景

#### Saga WhenDsl
- `functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean)`：过滤消息函数
- `functionName(functionName: String)`：按函数名称过滤
- `whenEvent(event: Any, state: Any?, ownerId: String, block: ExpectDsl<T>.() -> Unit)`：使用事件触发 Saga

#### Saga ExpectDsl
- `expectCommandType(commandType: KClass<out Any>)`：断言发送的命令类型
- `expectCommand(block: CommandMessage<*>.() -> Unit)`：验证命令内容
- `expectNoCommand()`：断言未发送命令

## 示例

请参阅[示例模块](../../example/)以获取全面的测试示例。