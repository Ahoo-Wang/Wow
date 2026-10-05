---
title: 定义命令
description: 定义命令载荷、目标聚合元数据与只返回领域事件的处理函数。
outline: deep
---

# 定义命令

命令是请求改变状态的祈使性载荷。它描述调用方想要发生什么；[聚合](../domain/aggregate.md)根据当前状态决定是否允许，并以领域事件表示已发生的事实。

普通命令由聚合 Handler 根据当前状态产生事件；Void 命令在 Dispatcher 层直接确认。

```mermaid
flowchart LR
    Command["命令载荷 + 元数据"] --> Void{"Void 命令？"}
    Void -->|是| Ack["Dispatcher 确认，不进入聚合 Handler"]
    Void -->|否| Handler["命令处理函数"]
    State["当前聚合状态"] --> Handler
    Handler --> Events["0..N 个领域事件"]
    Events --> Sourcing["onSourcing 更新状态"]
```

## 命令载荷与命令消息

命令载荷通常是一个 Kotlin `data class` 或 `object`。发送时，`toCommandMessage()` 会将载荷与命令 ID、请求 ID、聚合身份、owner、space、header、期望版本以及创建标记封装为 `CommandMessage<C>`。

```kotlin
@CreateAggregate
data class CreateOrder(
    val items: List<Item>,
    val address: ShippingAddress,
    val fromCart: Boolean,
)
```

载荷表达请求数据，不是运行时信封。需要版本控制时，在命令载荷上提供 `@AggregateVersion`；命令消息中的 `aggregateVersion` 则用于乐观并发检查。

## 目标聚合与命令元数据

`CommandMetadataParser` 从命令类型生成名称、目标聚合、聚合 ID、租户、owner、期望版本及创建、允许创建和 Void 标记。目标聚合可由命令自身实现 `NamedAggregate`，也可通过 `@AggregateName` 指定；`@AggregateId` 指定目标 ID，未标注时约定名为 `id` 的属性会被采用。

`@TenantId`、`@OwnerId` 与 `@AggregateVersion` 分别提供对应元数据；`@StaticAggregateId` 和 `@StaticTenantId` 提供静态值。命令自身携带的值优先于调用方传入的值。经 HTTP 发送时，自 9.3.0 起，与路由已确定的租户或拥有者矛盾的 `@TenantId`、`@OwnerId` 返回 `400`（见[请求身份](../open-api.md#请求身份)）。若命令和调用参数都不能解析出目标聚合，构造 `CommandMessage` 会失败。

## 命令处理函数

命令处理函数只做三件事：读取当前状态、检查业务不变量、返回一个或多个领域事件。数据库写入、事件发布和投影更新属于运行时处理链，不属于聚合决策。

```kotlin
@AggregateRoot
class Cart(private val state: CartState) {
    fun onCommand(command: ChangeQuantity): CartQuantityChanged {
        val item = state.items.firstOrNull { it.productId == command.productId }
            ?: throw IllegalArgumentException("商品不存在")
        return CartQuantityChanged(item.copy(quantity = command.quantity))
    }
}
```

约定名 `onCommand` 会被自动发现；自定义函数名或返回类型无法静态表达事件集合时，使用 `@OnCommand(returns = [...])`。第一个参数可以是具体命令、`CommandMessage<C>` 或 `ServerCommandExchange<C>`；其余参数可由 IoC 容器解析。处理函数可返回单个事件、多个事件或响应式类型；外部校验必须保留在响应式链路中。

命令先匹配为其确切类型声明的处理函数。自 9.3.0 起，没有确切匹配时，第一个参数是该命令父类或接口的处理函数也能匹配：最近的父类型优先，距离相同时父类先于接口，`Any` 类型的参数不参与匹配。最近距离上有多个父类型都有处理函数时（如 `class C : I1, I2` 且两者都有处理函数），第一个胜出，并打 WARN 列出其余的；要自己决定，就为该命令本身的类型声明处理函数。这样的命令仍需送达聚合：REST 命令路由与命令门面只接受已注册的命令类型（KSP 记录处理函数的参数类型），因此以这种方式处理的子类型要通过 `CommandGateway` 发送，或用 `@AggregateRoot(commands = [...])` 挂载以获得路由。after-command 函数与 `@OnError` 仍按命令的确切类型匹配：`include`/`exclude` 和 `@OnError` 的参数写子类型，而不是运行了处理函数的父类型。聚合为同一命令类型声明了两个处理函数时，仍由先找到的那个处理；自 9.3.0 起，启动时会打 WARN，指出被忽略的那个。其余参数解析不到时仍注入 `null`；自 9.3.0 起，参数类型不可空时第一次这样的调用会打 WARN。服务可有可无时，把参数声明为可空类型。

## 创建、允许创建与 Void 命令

`@CreateAggregate` 表示创建命令。创建命令的期望版本为未初始化版本，并从新状态开始处理，而不是恢复已有事件历史。

`@AllowCreate` 允许目标聚合不存在时按需创建；未标注时，找不到目标聚合的普通命令会失败。`AddCartItem` 是现有的允许创建示例。

是否创建只由这两个注解决定。其他命令携带期望版本 `0`（消息的 `aggregateVersion` 或 `Command-Aggregate-Version` 请求头）只是普通的乐观并发检查，不会让它变成创建命令：既没有 `@CreateAggregate` 也没有 `@AllowCreate` 的命令，目标聚合不存在时仍以 `NotFound` 失败。自 9.2.3 起；更早的版本把期望版本 `0` 当作创建命令。

`@VoidCommand` 不是“处理函数没有返回值”。它仍会发送到命令总线并成为 `isVoid` 命令，但 `CommandDispatcher` 会在聚合分发前确认并过滤它；因此不会调用聚合根、不会产生事件，也不会更新状态。此类命令仍应通过 `@AggregateRoot(commands = [...])` 挂载到聚合，例如 `ViewCart`。

## AfterCommand 与 OnError

`afterCommand` 约定名或 `@AfterCommand` 声明主命令成功后的后置函数。后置函数按 `@Order` 排序，`include` 和 `exclude` 用于限定命令类型；其返回的事件会追加到同一事件流，位于主命令事件之后。

`onError` 约定名或 `@OnError` 声明错误处理函数。运行时会先把原始错误记录到 exchange，再调用匹配的错误函数。命令仍然失败：该函数在 exchange 上换了错误时以新错误失败，否则以原始错误失败；函数自己抛出的错误则取而代之。默认处理器重试可恢复的失败时，错误函数只在最终失败后，在最近一次加载的聚合上执行一次（最后一次尝试在加载聚合前失败时，用更早一次尝试的聚合；没有任何尝试加载到聚合时不执行）；重试成功的命令不会调用它，函数怎样处理错误也不决定是否重试（自 9.2.3 起；更早的版本每次失败的尝试都会调用）。它用于观察或恢复框架允许的错误处理，不应成为绕过业务不变量的第二条写路径。

## 输入验证与业务不变量边界

调用边界负责载荷格式和字段约束，例如 Jakarta Validation、`CommandValidator` 及请求 ID 预检。聚合负责依赖当前状态的业务不变量，例如购物车容量或订单生命周期。

不要因为字段验证通过就跳过聚合检查：同一命令在不同事件历史下可能应被接受或拒绝。为每条不变量测试 Given 历史、When 命令、Expect 事件或错误，以及溯源后的状态。

## 下一步：发送命令

命令定义完成后，使用[发送命令](./sending.md)发送 `CommandMessage`，并通过[完成语义](./completion.md)选择满足调用方响应契约的等待阶段。
