---
title: 领域测试套件
description: 使用 wow-test 将聚合与 Saga 行为写成 Given → When → Expect 规格。
outline: deep
---

# 领域测试套件

`wow-test` 用内存中的领域运行时执行聚合与无状态 Saga 规格。它适合验证命令决策、领域事件、事件溯源后的状态和 Saga 产生的命令，不需要数据库或消息中间件。

::: warning 测试边界
领域规格通过只证明领域行为。它不证明 KSP 产物已打包、Spring 已正确装配、HTTP 路由可用、真实存储可恢复或鉴权有效。这些门禁见[Wow 应用测试](./application-testing.md)。
:::

::: tip 完成信号
每条已建模不变量都应至少有一个成功或拒绝规格；状态转换同时断言事件与溯源状态。领域层完成后，运行所属模块的 `test`/`check`，再进入应用集成门禁。
:::

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
dependencies {
    testImplementation("me.ahoo.wow:wow-test:${wowVersion}")
}
```

```groovy [Gradle(Groovy)]
dependencies {
    testImplementation "me.ahoo.wow:wow-test:${wowVersion}"
}
```

```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-test</artifactId>
    <version>${wow.version}</version>
    <scope>test</scope>
</dependency>
```
:::

Kotlin 断言使用项目测试栈中的 FluentAssert 扩展：

```kotlin
import me.ahoo.test.asserts.assert
```

## Given → When → Expect

| 阶段 | 要表达的问题 | 常用 DSL |
| --- | --- | --- |
| Given | 聚合此前发生了什么？ | `givenEvent`、`givenState`、`givenOwnerId`、`givenSpaceId`、`inject` |
| When | 现在执行什么？ | `whenCommand`、Saga 的 `whenEvent` |
| Expect | 结果是否符合不变量？ | `expectNoError`、`expectErrorType`、`expectEventType`、`expectState`、`expectCommand` |

优先用历史事件建立 Given。`givenState` 适合明确需要从某个状态版本开始的测试，但它跳过了事件重放，不能替代溯源行为验证。

## 聚合规格：事件与状态一起断言

下面的最小场景来自当前 `CartSpec`。它从未初始化聚合开始，设置 owner，执行添加商品命令，然后同时验证事件、业务状态和聚合元数据：

```kotlin
import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.AggregateSpec

class CartSpec : AggregateSpec<Cart, CartState>({
    on {
        val ownerId = generateGlobalId()
        val addCartItem = AddCartItem(
            productId = "productId",
            quantity = 1,
        )

        givenOwnerId(ownerId)
        whenCommand(addCartItem) {
            expectNoError()
            expectEventType(CartItemAdded::class)
            expectState {
                items.assert().hasSize(1)
            }
            expectStateAggregate {
                this.ownerId.assert().isEqualTo(ownerId)
            }
        }
    }
})
```

事件断言证明命令决策，状态断言证明事件已经由溯源函数正确应用。只断言其中一侧会漏掉另一侧的回归。

### 拒绝路径

拒绝路径应断言具体错误，并在重要场景确认状态或聚合元数据未被推进。当前 `OrderSpec` 覆盖了空商品、库存不足、价格不一致、未支付发货以及删除后继续操作等反例。

```kotlin
fork("Ship Before Payment") {
    val shipOrder = ShipOrder(stateAggregate.aggregateId.id)
    whenCommand(shipOrder) {
        expectErrorType(IllegalStateException::class)
        expectState {
            paidAmount.assert().isEqualTo(BigDecimal.ZERO)
            status.assert().isEqualTo(OrderStatus.CREATED)
        }
    }
}
```

不要把所有失败都写成 `expectError()`。错误类型属于业务契约时，使用 `expectErrorType(...)` 使规格能区分不同拒绝原因。

### 分支与引用点

`fork` 从一个已验证状态继续执行，适合订单创建后的支付、发货、收货，以及同一起点的非法转换。每个分支拥有独立的后续状态，不会污染兄弟分支。

在当前 Expect 阶段直接分支：

```kotlin
fork(name = "Remove CartItem") {
    whenCommand(RemoveCartItem(setOf(addCartItem.productId))) {
        expectEventType(CartItemRemoved::class)
        expectState {
            items.assert().isEmpty()
        }
    }
}
```

需要稍后从同一点展开时，先 `ref("AggregateDeleted")`，再在顶层使用 `fork(ref = "AggregateDeleted", ...)`。引用点应代表已经验证过的业务状态，而不是仅为减少几行设置代码。

```kotlin
fork(ref = "AggregateDeleted", name = "Recover") {
    whenCommand(DefaultRecoverAggregate) {
        expectNoError()
        expectStateAggregate {
            deleted.assert().isFalse()
        }
        fork(name = "Recover Again") {
            whenCommand(DefaultRecoverAggregate) {
                expectErrorType(IllegalStateException::class)
            }
        }
    }
}
```

## 注入领域依赖

当命令处理函数依赖领域规格服务时，通过 `inject` 注册测试实现。当前 `OrderSpec` 为 `DefaultCreateOrderSpec` 注入库存和定价服务，从而分别覆盖成功、库存不足和价格不一致。

```kotlin
inject {
    register(DefaultCreateOrderSpec(inventoryService, pricingService))
}

whenCommand(CreateOrder(orderItems, SHIPPING_ADDRESS, false)) {
    expectNoError()
    expectEventType(OrderCreated::class)
    expectState {
        status.assert().isEqualTo(OrderStatus.CREATED)
        totalAmount.assert().isEqualTo(totalAmount)
    }
}
```

这里注入的是领域边界的测试实现。真实网络客户端、数据库和 Broker 不属于该层，应放到应用集成测试。

## 无状态 Saga 规格

`SagaSpec` 的 When 是输入事件，Expect 是 Saga 发送的命令。当前 `CartSagaSpec` 验证从购物车创建订单后删除对应商品：

```kotlin
import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.SagaSpec

class CartSagaSpec : SagaSpec<CartSaga>({
    on {
        val ownerId = generateGlobalId()
        val orderItem = OrderItem(
            id = generateGlobalId(),
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )

        whenEvent(
            event = mockk<OrderCreated> {
                every { items } returns listOf(orderItem)
                every { fromCart } returns true
            },
            ownerId = ownerId,
        ) {
            expectCommandType(RemoveCartItem::class)
            expectCommand<RemoveCartItem> {
                aggregateId.id.assert().isEqualTo(ownerId)
                body.productIds.assert().hasSize(1)
                body.productIds.assert().first().isEqualTo(orderItem.productId)
            }
        }
    }
})
```

对应反例应使用 `expectNoCommand()`，例如 `OrderCreated.fromCart == false` 时不删除购物车商品。Saga 规格验证命令意图与内容；Broker 重投和外部副作用幂等仍需真实适配器测试。

## 选择最窄的断言

| 目标 | DSL |
| --- | --- |
| 没有错误 / 特定错误 | `expectNoError()` / `expectErrorType(...)` |
| 事件数量、顺序或类型 | `expectEventCount`、`expectEventIterator`、`expectEventType` |
| 事件体字段 | `expectEventBody<E> { ... }` |
| 业务状态 | `expectState { ... }` |
| owner、版本、删除标记等聚合元数据 | `expectStateAggregate { ... }` |
| Saga 命令数量、类型或内容 | `expectCommandCount`、`expectCommandType`、`expectCommand<C>` |

断言业务可观察结果，不要复制框架内部实现。Kotlin 值断言统一使用 `.assert()`，避免在同一套规格中混用断言风格。

## 覆盖率证据怎么读

当前仓库的 `:example-domain` 在 Gradle 中将 `jacocoTestCoverageVerification` 下限设为 `0.8`，并让该任务依赖 `test` 和报告生成。这是该示例模块当前的仓库门禁，不是 `wow-test` 自动保证的覆盖率，也不是所有应用都必须照搬的数值。

旧文档截图、历史覆盖率或经验性缺陷数据只能说明当时的样本。评价当前变更时，以本次测试输出、当前覆盖率报告和项目自己的阈值为准。

## 运行与下一层

在本仓库验证示例与 DSL：

```bash
./gradlew :wow-test:check :example-domain:check
```

业务应用应改为自己的领域模块路径。该命令通过后，下一层是[Wow 应用测试](./application-testing.md)：验证生成元数据、运行时装配、HTTP、真实 Adapter、重启恢复与安全反例。修改 Wow 框架本身时，则使用[框架测试与基准](./test-runtime.md)中的仓库任务。
