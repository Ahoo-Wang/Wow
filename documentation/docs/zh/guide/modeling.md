---
title: 聚合建模
description: 从业务不变量出发，在 Wow 中分离命令决策与事件溯源状态。
outline: deep
---

# 聚合建模

Wow 聚合把一次业务变更拆成两个方向：命令处理函数根据当前状态决定“允许发生什么”，溯源函数根据已发生的领域事件重建“现在是什么”。建模的核心不是注解数量，而是让每个状态变化都能由事件解释。

::: tip 完成信号
聚合完成建模时，每条不变量都有明确的成功事件、拒绝结果和确定性的溯源结果。下一步应在[测试套件](./test-suite.md)中把这些行为写成 Given → When → Expect 规格。
:::

## 先写不变量，再写处理函数

以仓库中的购物车为例，业务规则可以先写成一张决策表：

| 当前状态与命令 | 决策结果 | 溯源后的状态 |
| --- | --- | --- |
| 商品不存在，添加商品 | `CartItemAdded` | 商品加入 `items` |
| 商品已存在，再次添加 | `CartQuantityChanged` | 替换对应商品数量 |
| 商品数已达到 `MAX_CART_ITEM_SIZE` | 拒绝命令 | 状态不变，不产生业务事件 |
| 删除一组商品 | `CartItemRemoved` | 过滤对应 `productId` |

这张表直接决定三类代码：命令类型表达意图，事件类型表达事实，状态对象只在溯源事件时改变。

## 推荐模式：命令与状态组合

`example-domain` 的 `Cart` 和 `Order` 都把命令处理对象与状态对象分开：

```text
Command -> Command Aggregate -> Domain Event -> State Aggregate
                    reads state                 mutates state
```

命令聚合持有状态的只读视图，并返回一个或多个事件：

```kotlin
@AggregateRoot(commands = [MountedCommand::class, ViewCart::class, MockVariableCommand::class])
class Cart(private val state: CartState) {

    @OnCommand(returns = [CartItemAdded::class, CartQuantityChanged::class])
    fun onCommand(command: AddCartItem): Any {
        require(state.items.size < MAX_CART_ITEM_SIZE) {
            "购物车最多只能添加[$MAX_CART_ITEM_SIZE]个商品."
        }
        state.items.firstOrNull { it.productId == command.productId }?.let {
            return CartQuantityChanged(
                changed = it.copy(quantity = it.quantity + command.quantity),
            )
        }
        return CartItemAdded(
            added = CartItem(command.productId, command.quantity),
        )
    }
}
```

状态对象把 setter 设为私有，只通过事件溯源函数更新：

```kotlin
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

这种分离让命令函数无法顺手改状态，也让同一事件历史能够重复重放。

## 其他受支持的组织方式

Wow 的处理函数发现机制不要求聚合必须采用一种类结构。根据模型规模，可以选择：

| 方式 | 适用情况 | 必须守住的边界 |
| --- | --- | --- |
| 命令对象组合状态对象 | 默认选择，适合大多数聚合 | 命令侧只读状态，状态侧只处理事件 |
| 命令对象继承状态对象 | 已有模型需要以继承组织能力 | 状态 setter 仍应私有，命令函数不得直接修改状态 |
| 命令与状态放在单一类 | 很小的模型或兼容既有代码 | 团队必须通过结构和测试确保命令路径不直接写状态 |

组合方式通常更容易审查：谁做决策、谁改变状态一眼可见。复杂聚合也可以使用命令和状态的基类，但不要为了“以后可能复用”预先增加继承层级。

Wow 同时支持 Kotlin 与 Java。完整的 Java 组织方式见[银行转账示例](../reference/example/transfer)。

## 命令处理约定

- `@AggregateRoot` 显式标记聚合并参与元数据生成；在公共模型中应优先明确标记。
- 约定名 `onCommand` 可以省略 `@OnCommand`；需要自定义函数名或补充返回事件元数据时使用注解。
- 第一个参数可以是具体命令、`CommandMessage<C>` 或 `ServerCommandExchange<C>`；其余参数可由 IoC 容器解析。
- 返回值可以是单个事件、多个事件或响应式类型。返回类型无法静态表达事件集合时，用 `@OnCommand(returns = [...])` 明确列出。
- 需要外部校验时保持响应式链路。`Order` 通过注入的 `CreateOrderSpec` 返回 `Mono<OrderCreated>`，没有在命令路径中阻塞。

命令处理函数只做三件事：读取当前状态、检查不变量、返回事件。数据库写入、事件发布和投影更新属于运行时，不应塞进聚合决策。

## 溯源约定

- 状态类型必须提供以下任一受支持构造函数：`ctor()`、`ctor(id)` 或 `ctor(id, tenantId)`；构造参数最多两个且都必须是 `String`。仓库示例通常使用约定名 `id`，例如 `CartState(val id: String)`。
- 约定名 `onSourcing` 可以省略 `@OnSourcing`；其他命名应显式标记。
- 参数可以是事件体，也可以是包含元数据的领域事件。
- 溯源函数不返回事件，不访问外部服务，不读取当前时间或随机数。
- 多事件返回值的顺序也是契约。状态只消费会改变本聚合状态的事件；供其他组件消费的通知事件可以不改变状态。

确定性是硬约束：相同的初始状态与事件序列必须得到相同结果，否则历史重放、快照校验和恢复都不可靠。

## 生命周期不变量

订单示例展示了状态机式不变量：

| 命令 | 允许状态 | 事件与下一状态 |
| --- | --- | --- |
| `ChangeAddress` | `CREATED` | `AddressChanged`，状态仍为 `CREATED` |
| `PayOrder` | `CREATED` | `OrderPaid`，足额时进入 `PAID` |
| `ShipOrder` | `PAID` | `OrderShipped`，进入 `SHIPPED` |
| `ReceiptOrder` | `SHIPPED` | `OrderReceived`，进入 `RECEIVED` |

无效转换应在命令侧被拒绝，状态侧不需要猜测命令意图。删除与恢复属于 Wow 提供的聚合生命周期，同样要测试删除后拒绝访问、恢复成功和重复恢复失败。

## 路由、后置与错误钩子

路由配置是公开契约的一部分。例如：

```kotlin
@AggregateRoute(enabled = false)
class InternalAggregate(val id: String)
```

禁用后，该聚合的全部自动 HTTP routes 都不会生成，包括命令以及状态、快照、事件等查询 contributor；聚合本身的领域命令与溯源语义不变。

`@AfterCommand` 可在主命令成功后追加事件，多个钩子可通过 `@Order` 排序；`include` 与 `exclude` 用于限定命令类型。`@OnError` 可观察命令失败并执行框架允许的错误处理。它们不应成为绕过核心不变量的第二条写路径：会改变聚合事实的结果仍应由明确的领域事件表达。

## 从模型进入测试

为每条不变量准备以下四项即可进入领域测试：

1. Given：重放哪些历史事件，或从未初始化状态开始；
2. When：执行哪个命令，带有什么 owner、space 或注入服务；
3. Expect event/error：应该产生哪些事件，或拒绝为哪种错误；
4. Expect state：事件溯源后哪些状态字段和聚合元数据必须成立。

领域规格通过后，再进入[Wow 应用测试](./application-testing.md)验证 KSP 元数据、Spring 装配、HTTP、真实存储、恢复和安全边界。领域 DSL 通过不等于应用已经可以发布。
