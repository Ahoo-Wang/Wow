---
title: 编写聚合
description: 编写聚合的命令、命令函数和状态；在决定中使用领域服务、创建聚合、附加结果、预留键和定时命令。
outline: deep
---

# 编写聚合

聚合是做出决定的单位：它一次接收一条命令，查看自己的状态，决定发生哪些事件。本页以 eShop 的订单为例写一个聚合。背后的模型见[决定与演化](../concepts/programming-model)。

## 代码放在哪里 {#where-the-code-goes}

一个聚合跨越它所在上下文的两个模块：

| 模块 | 包含 | 由谁读取 |
| --- | --- | --- |
| `*-api` | 命令和事件，普通的数据类 | 服务本身，以及发送它的命令或读取它的事件的每个对端 |
| `*-domain` | 根类及其状态类 | 只有服务本身 |

编译器在构建期读取领域模块，生成聚合的模型、它的绑定以及它那部分 Manifest。运行时不扫描任何东西。

## 编写命令 {#write-the-commands}

命令是 api 模块里的一个数据类。它承载调用方要求的内容，从不承载聚合是谁：ID 来自路由或发送命令的 Saga，租户、所有者和空间来自调用方，期望版本来自 `If-Match`。

<!-- sample: examples/eshop/ordering/ordering-api/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/api/order/OrderCommands.kt#submit-order -->

```kotlin
/**
 * Submits an order of [items]. The basket context sends it when its buyer checks out, with the order's id fixed by the
 * basket. The order belongs to the buyer: a saga's command carries the scope of the basket it is derived from, whose
 * owner is its buyer, and the order takes its owner from the command that creates it. An order has
 * items, each valid ([OrderItem]'s constraints): the gateway checks that before the order decides.
 */
@CreateAggregate
@Summary("Submit an order")
data class SubmitOrder(
    @field:NotEmpty @field:Valid val items: List<OrderItem>,
)
```

`@Summary` 为命令提供一行摘要，用于 Manifest 和 OpenAPI 文档。不带数据的命令是一个 `data object`，例如 `ShipOrder`。

### 检查它的形状 {#check-its-shape}

只关乎命令本身的规则，例如数量为正、名称不为空白、订单有条目，是命令类上的 Jakarta Bean Validation 约束，用 Kotlin 的 `@field:` 目标：

<!-- sample: examples/eshop/basket/basket-api/src/main/kotlin/me/ahoo/wow/example/eshop/basket/api/basket/BasketCommands.kt#add-item -->

```kotlin
/**
 * Adds [quantity] of [productId] at [unitPrice] to the basket, creating the basket on its first item. Adding a product
 * already in the basket adds to its quantity, at the new price. The constraints are the command's shape: the gateway
 * checks them before the basket decides, and answers a violation `wow.command.invalid` (400).
 */
@AllowCreate
@Summary("Add an item to the basket")
data class AddBasketItem(
    val productId: String,
    @field:PositiveOrZero val unitPrice: BigDecimal,
    @field:Positive val quantity: Int,
)
```

网关在聚合决定之前检查它接收的每条命令的约束：来自路由、进程内 `CommandClient`、Saga 或另一个上下文的命令都一样。违反约束时回答 `wow.command.invalid`（400），problem 列出每个违反项的属性路径（`items[0].quantity`）、约束（`Positive`）和消息。属性标注 `@field:Valid` 时检查嵌套的值，`SubmitOrder` 的条目就是这样。api 模块依赖 `jakarta.validation:jakarta.validation-api`，服务需要一个 Bean Validation 实现（[托管在 Spring Boot 上](./spring-boot#what-the-class-path-decides)）。

需要聚合状态或调用方的规则留在命令函数里，作为带错误码的拒绝（[拒绝与错误码](./rejections)）：下面订单的函数已经没有要检查的了。

命令能否创建它的聚合，由它的类决定：

| 命令类 | 何时被接受 |
| --- | --- |
| `@CreateAggregate` | 聚合尚不存在；否则 `wow.aggregate.exists` |
| `@AllowCreate` | 无论聚合是否存在 |
| 两者都没有 | 聚合已存在；否则 `wow.aggregate.not-found` |

每条命令的路由是 `POST /<context>/<aggregate>/{id}/<command>`，命令名采用 kebab case，与 Manifest 列出的路由一致：`POST /eshop-ordering/order/{id}/submit-order`。创建命令还提供不带 `{id}` 的路由，由服务端为聚合分配 ID。准入会先查找同一个请求 ID 已经创建过的聚合并复用它的 ID，所以同一个创建发两次只会创建一个聚合。

## 编写根 {#write-the-root}

根是一个标注了 `@AggregateRoot`、由它的状态构造的类。它的命令函数做出决定：每个函数接收一条命令，返回它的决定。

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#submit -->

```kotlin
fun onCommand(command: SubmitOrder): OrderSubmitted = OrderSubmitted(command.items)
```

编译器找出每个标注 `@OnCommand` 或名为 `onCommand` 的非私有函数。第一个参数是命令；返回值是决定：

| 返回 | 含义 |
| --- | --- |
| `Decision.events(e1, e2, …)` | 这些事件，作为一条事件流一起追加 |
| `Decision.NoOp` | 命令被接受但什么都不改变；不写入任何东西 |
| `Decision.reject(code, message)` | 命令被拒绝；不写入任何东西，调用方拿到错误码（[拒绝与错误码](./rejections)） |
| 一个事件，或一个事件列表 | 这些事件 |
| `Unit`、`null` 或空列表 | 什么都不改变 |

返回 `Decision` 的函数没有告诉编译器它能产生哪些事件，所以要在 `@OnCommand(produces = [...])` 里列出它们。Manifest 会发布这份列表。返回类型本身已经指明事件的函数，例如返回某个事件类或它们的密封类型，不必重复列出。

聚合以根类名的 snake case 命名（`Order` 是 `order`，`ViewPreferences` 是 `view_preferences`），或者用 `@Name` 指定。

## 读取准入后的命令 {#read-the-admitted-command}

需要准入从请求中得知的信息的命令函数，接收 `Command<X>` 而不是 `X`：

| 成员 | 包含 |
| --- | --- |
| `body` | 命令 `X` |
| `ref` | 聚合：它的上下文、名字、租户和 ID |
| `scope` | 调用方的所有者和空间 |
| `headers` | 宿主保留的请求事实（`wow.http.request-facts`），例如调用的应用 |
| `causation`、`operator` | 命令所属的链，以及它以谁的名义记录 |

购物车的 ID 就是买家的用户 ID，所以它的第一条命令用它来核对调用方：

<!-- sample: examples/eshop/basket/basket-domain/src/main/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/Basket.kt#add-item -->

```kotlin
fun onCommand(command: Command<AddBasketItem>): BasketItemAdded {
    val owner = command.scope.ownerId
    if (owner != null && owner != command.ref.id) {
        reject(Rejections.NOT_OWNER, "The basket ${command.ref.id} is not $owner's.")
    }
    val adding = command.body
    return BasketItemAdded(adding.productId, adding.unitPrice, adding.quantity)
}
```

## 使用领域服务 {#take-domain-services}

命令之后的参数是领域服务，或者 `DecideScope`。服务会成为生成的模型的一个参数，宿主在构建模型时绑定一次：取应用中该类型唯一的 Bean，或者它的 primary Bean。在参数上写 `@Name("…")` 则取这个名字的 Bean，于是同一类型的两个参数可以取两个不同的 Bean。

订单把它的支付策略作为服务接收，并在库存确认后用 `DecideScope` 为自己定时取消：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#confirm-stock -->

```kotlin
/**
 * Confirms one product. A confirmation the order does not wait for changes nothing: one read again from the
 * stock stream, or one that arrives after another product's shortage cancelled the order (that reservation is
 * released when the inventory hears of the cancellation).
 */
// Decision: the last confirmation produces two events, the item's and the order's.
@OnCommand(
    produces = [OrderStockItemConfirmed::class, OrderStockConfirmed::class],
    schedules = [CancelOrder::class],
)
fun onCommand(command: ConfirmOrderStock, paymentPolicy: PaymentPolicy, scope: DecideScope): Decision {
    val waiting = state.status == OrderStatus.AWAITING_VALIDATION &&
        command.productId in state.products() &&
        command.productId !in state.confirmedProducts
    if (!waiting) {
        return Decision.NoOp
    }
    val confirmed = OrderStockItemConfirmed(command.productId)
    if (state.confirmedProducts + command.productId != state.products()) {
        return Decision.events(confirmed)
    }
    scope.schedule(
        PAYMENT_GRACE_PERIOD,
        ScheduleRule.after(paymentPolicy.gracePeriod),
        CancelOrder(PAYMENT_TIMEOUT)
    )
    return Decision.events(confirmed, OrderStockConfirmed)
}
```

服务可以做 I/O。会阻塞线程的函数，例如调用 JDBC 的函数，要标注 `@Blocking`，它会在虚拟线程上运行。函数也可以是 `suspend`，或者返回 Reactor 的 `Mono`，这需要类路径上有 `wow-reactor`。

## 向内核要更多东西 {#ask-the-kernel-for-more}

`DecideScope` 是一次决定除了事件之外还能向内核要的东西：

| 成员 | 作用 |
| --- | --- |
| `command` | 正在决定的命令，类型为 `Command<*>` |
| `ids` | 节点的 ID 生成器，用于决定需要的 ID |
| `putResult(key, value)` | 往事件追加之后给调用方的回答里加一个值。它不存储：重复的请求不会再得到它 |
| `reject(code, message)` | 用一个错误码拒绝命令，可以附带用于 problem 的 `extensions`（[拒绝](./rejections#reject-with-a-code)） |
| `reserve(namespace, key)` | 为这个聚合预留一个唯一键，例如用户名；被另一个聚合持有时返回 `false`。`ReservationTenancy` 参数扩大它的范围（[唯一键](./unique-keys#reserve-a-key)） |
| `release(namespace, key)` | 在事件追加之后，释放这个聚合持有的一个键；使用预留它时的 `ReservationTenancy` |
| `onAppendFailure { … }` | 在确定事件没有追加时，撤销一个外部动作（[见下文](#undo-an-outside-act)） |
| `afterAppend { … }` | 事件追加之后运行；失败只记日志，不抛出（[见下文](#undo-an-outside-act)） |
| `schedule(key, rule, command, target)` | 定时一条命令，发往这个聚合，除非 `target` 指定了另一个。它与事件一起写入，要么都写入，要么都不写 |
| `cancelSchedule(key)` | 取消这个聚合在 `key` 下保持的定时 |

结账时用它提交的订单 ID 回答调用方：

<!-- sample: examples/eshop/basket/basket-domain/src/main/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/Basket.kt#checkout -->

```kotlin
@Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
fun onCommand(command: CheckoutBasket, scope: DecideScope): BasketCheckedOut {
    if (state.items.isEmpty()) {
        reject(Rejections.EMPTY, "The basket is empty.")
    }
    val orderId = "${scope.command.ref.id}-${state.checkouts + 1}"
    scope.putResult(ORDER_ID_RESULT, orderId)
    return BasketCheckedOut(orderId, state.items)
}
```

调用方从结果的 `results` 里读取它：`{"orderId": "alice-1"}`。

定时命令的函数要在 `@OnCommand(schedules = [...])` 里声明命令的类，就像订单对 `CancelOrder::class` 做的那样：编译器读不到函数体里的调用，而 Manifest 按完整类型名列出它们。

## 撤销对外部的操作 {#undo-an-outside-act}

决策可能在事件追加之前就对聚合之外做了事：预授权一张卡，在另一个系统里占一个座位。`onAppendFailure` 登记怎样撤销这件事，内核在确知事件没有追加时执行它：

<!-- sample: fragment -->

```kotlin
@OnCommand
suspend fun onCommand(command: PayOrder, scope: DecideScope): Decision {
    val authorization = payments.authorize(state.totalAmount, key = scope.command.requestId)
    scope.onAppendFailure { payments.void(authorization) }
    return Decision.events(OrderPaid(authorization.id))
}
```

以下情况会执行补偿：

- 决策拒绝了命令（[三种方式](./rejections)中的任意一种），或者什么也不追加（`Decision.NoOp`）；
- 决策函数抛出异常；
- 追加遇到版本冲突。每次尝试先撤销自己做的事，命令再基于更新后的状态重新决定；
- 存储拒绝了追加，或者以确定没有写入的方式失败；
- 追加时发现这个请求已经由另一次尝试提交。

追加结果未知时不执行。内核会先读取这次追加本应占用的版本；只有仍然无法判断时，才回答 `wow.command.unknown`，此时事件可能已经提交。进程崩溃时也无法执行。所以，命令丢失后不能留下的外部操作，还需要自己的清理手段：外部系统里的过期机制，或者读取日志的对账。

- **顺序。** 补偿按登记的逆序逐个执行，每个都会等待完成，调用方已经离开时也一样。没有超时：卡住的补偿会挡住这个聚合的命令，所以要给它的调用设上限。
- **失败。** 一个补偿失败不会阻止其余的补偿。失败会被记日志，或附加到它所撤销的那个失败上，命令的应答不变。
- **两次。** 基于已落后于日志的缓存状态被拒绝的命令会重新决定，第一次尝试先被补偿。外部操作因此可能做了两次、撤销一次，所以要以命令为键，如上例中的 `requestId`。
- **预留的键。** `reserve` 会登记自己的补偿：事件没有追加时，这次尝试持有的键会被放掉。结果未知或进程崩溃时，持有在 30 秒后过期。

`afterAppend` 是另一面：事件追加之后、应答调用方之前，按登记顺序执行。重复请求不执行，任何没有追加的结果也不执行。失败只记日志，事件仍然已提交。订阅可能在它结束之前就看到这次提交；进程在两者之间崩溃，它就被跳过。所以它适合可以丢失的工作，例如预热缓存。必须跟随每次提交的工作应放在反应器或 Saga 中，由它们读取已提交的事件。

## 编写状态 {#write-the-state}

状态类为每个事件提供一个演化函数，标注 `@OnSourcing` 或名为 `onSourcing`。演化函数应用一个事件：它不接收服务，也不做 I/O。

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderState.kt#sourcing -->

```kotlin
fun onSourcing(event: OrderSubmitted) {
    items = event.items
    totalAmount = event.items.fold(BigDecimal.ZERO) { total, item ->
        total + item.unitPrice.multiply(BigDecimal(item.quantity))
    }
    status = OrderStatus.SUBMITTED
}
```

- 聚合能产生的每个事件都需要一个演化函数，即使它什么都不改变。
- 原地修改状态的演化函数不返回任何东西。返回状态类型的演化函数则返回新的状态，用于不可变状态。
- 在第一个事件之前就给每个属性一个值。内核按状态的原样序列化它，用于快照和发布的事件流。
- 命令函数从不修改状态，只读取它。测试 DSL 比较每次决定前后的状态，若有改变就让测试失败。

## 在每条命令之后运行代码 {#run-code-after-every-command}

标注 `@AfterCommand` 或名为 `afterCommand` 的函数，在它适用的命令函数之后运行：`include` 里的命令函数，`include` 为空时是全部，再减去 `exclude` 里的。它看到的是命令函数看到的那个状态，返回事件，这些事件在同一条流里追加在命令自己的事件之后。命令被拒绝时它不运行，它也不能拒绝。它失败时整条命令失败，什么都不写。

## 生命周期命令 {#lifecycle-commands}

每个聚合还处理 `me.ahoo.wow.api.lifecycle` 中的五条系统命令：`DeleteAggregate`、`RestoreAggregate`、`TransferOwner`、`TransferSpace` 和 `ApplyTags`。它们改变的是聚合的元数据，而不是它的状态；已删除的聚合拒绝除 `RestoreAggregate` 之外的每条命令。`@AggregateRoot(lifecycle = [...])` 可以收窄这份列表；空列表则一条都不启用。

## 测试 {#test-it}

用 `aggregateSpec` 测试每个命令函数：给定这些事件，执行这条命令，期望得到这些事件和这个状态。做法见[测试领域代码](./testing)。

## 下一步 {#next}

- [拒绝与错误码](./rejections)：声明命令被拒绝时用的错误码。
- [编写 Saga](./sagas)：从聚合的决定派生命令。
