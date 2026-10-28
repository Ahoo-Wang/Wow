---
title: 编写 Saga
description: 从已提交的事件派生命令，处理它们的成功与拒绝，同步调用另一个上下文，以及定时命令，所有请求都是确定的。
outline: deep
---

# 编写 Saga

Saga 把一个聚合的决定变成发往其他聚合的命令：等待校验的订单预留库存，预留确认后确认订单，预留被拒则取消订单。Saga 自己不持有状态。它读取已提交的事件、返回命令，由运行时发送这些命令。本页编写 eShop 的订单流程。Saga 怎样收到它的事件，见[订阅、失败与重放](../concepts/subscriptions)。

## 从事件派生命令 {#derive-commands-from-events}

Saga 是一个标注了 `@Saga` 的类。每个处理函数标注 `@OnEvent`，接收一个已提交的事件，需要时再接收 `SagaContext`，以 `CommandRequest` 的形式返回它派生的命令：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#derive -->

```kotlin
@OnEvent
fun onOrderSubmitted(
    event: StateTypedEvent<OrderState, OrderSubmitted>
): CommandRequest<AwaitStockValidation> = CommandRequest.of(AwaitStockValidation, event.ref.id)
```

- **它接收什么。** `StateTypedEvent<S, E>` 对已提交流中的每个事件 `E` 调用一次处理函数，`event` 是那个事件，`state` 是整次提交之后聚合的状态。`StateEvent<S>` 每条流调用一次，带着它全部的 `events`。状态类型 `S` 选出模块所在上下文中以它为状态的聚合。
- **它返回什么。** 一个 `CommandRequest`、多个（`List`、任意 `Iterable`、`Sequence` 或 `Flow`），或者 `null`、空列表或 `Unit` 表示没有命令。运行时把每个请求转成一条确定的命令（见下文）。
- **哪些命令。** 返回类型指明了命令类时（`CommandRequest<ReserveStock>`、它们的列表、一个密封类型），编译器就知道它们。没有指明时，例如 `List<CommandRequest<*>>`，要在 `@OnEvent(derives = [...])` 里声明它们，这样 Manifest 才会列出它们，等待也才能跟随 Saga 进入它们的上下文；两者都没有的处理函数，编译器会给出警告。
- **它的名字。** 每个处理函数都是一个独立的订阅，名为 `<processor>.<function>`：`OrderProcessSaga.onOrderAwaitingValidation`。函数上的 `@Name("…")` 改用它命名，所以给改名后的函数加上 `@Name("<原来的名字>")`，订阅、它的检查点和执行都保持不变。
- **它的依赖。** Saga 的构造函数参数，从应用的 Bean 绑定。

## 构建命令 {#build-the-commands}

| 请求 | 派生 |
| --- | --- |
| `CommandRequest.of(body, id)` | 一条发往聚合 `id` 的命令，聚合类型是决定 `body` 类的那个类型，在这次提交的租户中 |
| `CommandRequest.of(body)` | 一条发往该类型新聚合的创建命令，由目标为它分配 ID |
| `CommandRequest.to(ref, body)` | 一条发往 `ref` 所指聚合的命令，可以是任何类型和租户 |
| `CommandRequest.of(body, type = type)` | 一条发往 `type` 类型新聚合的创建命令，由目标为它分配 ID |

没有类型的请求从各上下文声明的命令中找出目标的类型：本服务自己的 Manifest，以及每个对端 api jar 携带的契约。类路径上没有任何上下文决定这个类，或者有多个聚合决定它时，处理函数失败；这时给请求指定类型。`targetTenant` 和 `targetScope` 把命令发往另一个租户或作用域，`expectedVersion` 要求目标处于那个版本。

每条派生命令都是确定的：

- 它的 ID 和请求 ID 来自这次执行（`subscription:ref@version`）和它请求的内容：它的目标、它的命令类型，以及这次执行在它之前向同一目标派生过多少条同类型的命令。再次处理这次提交时，无论处理函数以什么顺序构建命令，哪怕某次读取的结果顺序变了，都会派生出相同的请求，每个目标对每个请求只决定一次。给出 `requestId` 的请求以它作为业务键代替计数，所以无论处理函数还派生了什么，这条命令的 ID 都不变。
- 它带着派生它的那次提交的操作人、所有者和空间。它创建的聚合属于同一个所有者：购物车结账创建的订单属于购物车的买家。派生命令是受信任的，所以目标的作用域守卫不检查它们：目标错了也不会被拒绝。请在 `sagaSpec` 中测试目标。
- 订阅发送它，不等待任何结果：带 `wait` 或 `timeout` 的请求会让处理函数失败。
- `context.ids` 为命令创建的聚合生成一个 ID。再次处理这次提交会生成一个新的 ID，所以只把它用于创建命令本身，由创建命令的请求 ID 防止目标创建两次。没有 ID 的创建请求把 ID 留给目标，每次重试都保留第一次的 ID。

## 命令发往哪里 {#where-the-commands-go}

| 目标 | 怎样发送 | Saga 这一跳何时完成 |
| --- | --- | --- |
| 本上下文 | 节点在 Saga 的处理之内决定它 | 它被提交时 |
| 另一个上下文 | 它的命令主题 `wow.<context>.commands` | broker 收到它时 |

执行其他上下文的命令的服务要设置 `wow.kafka.command-intake.enabled: true`。Saga 发出其他上下文命令的节点需要命令传输，生产环境中是 Kafka（`wow.kafka.bootstrap-servers`），没有它就不会启动。主题见 [Kafka](../operate/kafka)。

## 得知命令已成功 {#learn-that-a-command-succeeded}

派生的命令默认不给 Saga 任何回答：命令在自己的上下文中提交后，或者发往另一个上下文的命令被 broker 收下后，Saga 这一跳就结束了。它的成功本来就有自己的事实，即目标提交的事件，所以 Saga 得知成功的方式与得知任何事情相同：

- **跟随目标的事件流。** 像处理其他事件一样，处理成功所产生的事件。支付 Saga 请求一笔支付，在这笔支付自己的 `PaymentSucceeded` 提交后为订单付款：

  <!-- sample: examples/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-command -->

  ```kotlin
  @OnEvent
  fun onPaymentSucceeded(
      event: StateTypedEvent<PaymentState, PaymentSucceeded>
  ): CommandRequest<PayOrder> = CommandRequest.of(PayOrder(event.ref.id, event.state.amount), event.state.orderId)
  ```

  另一个上下文的目标通过它发布的事件流跟随：处理函数接收那个上下文的事件类，由其契约指明所属聚合（[发布与消费事件流](./published-streams)）。

- **设置截止时间。** 成功可能永远不会到来时，定时发出用于补偿的命令，并在成功到来时取消它：

  <!-- sample: fragment -->

  ```kotlin
  @OnEvent(schedules = [CancelOrder::class])
  fun onOrderAwaitingStock(
      event: StateTypedEvent<OrderState, OrderAwaitingStock>,
      context: SagaContext,
  ): CommandRequest<ReserveStock> {
      val deadline = CommandRequest.of(CancelOrder("No stock in time."), event.ref.id)
      context.schedule(STOCK_DEADLINE, ScheduleRule.after(10.minutes), deadline)
      return CommandRequest.of(ReserveStock(event.ref.id), event.state.productId)
  }

  @OnEvent
  fun onOrderStockConfirmed(event: StateTypedEvent<OrderState, OrderStockConfirmed>, context: SagaContext) {
      context.cancel(STOCK_DEADLINE, event.ref)
  }
  ```

  目标为自己定时这条命令，所以它必须能决定这条命令。到期时被目标拒绝的定时命令只会记日志，不会重试，也不会交给任何 `@OnRejected`：像上面那样在成功时取消定时，或者给目标一条它在成功之后会按状态拒绝的命令。只有 `@OnEvent` 处理函数能定时或取消定时。

- **不写入任何东西的成功**不产生事件，所以 Saga 什么也看不到：以 `Decision.NoOp` 接受命令的目标什么也不提交。需要让别人知道时，让目标改为决定一个业务事件。

### 选择接收回执 {#act-on-a-command-s-success}

必须知道自己的命令已经提交的 Saga（因为目标的事件流没有发布给它，或者成功不产生事件），声明一个针对该命令类型的 `@OnCommitted` 处理函数。它接收 `CommandCommitted<C>`，并返回它再派生出的命令：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#committed -->

```kotlin
/** A product the inventory reserved confirms it on the order that asked for it. */
@OnCommitted
fun onReserveCommitted(
    committed: CommandCommitted<ReserveStock>
): CommandRequest<ConfirmOrderStock> =
    CommandRequest.of(ConfirmOrderStock(committed.ref.id), committed.source.id)
```

`source` 是那次派生出命令的提交所属的聚合，这里是订单；`ref` 是提交了命令的聚合，这里是商品的库存。只有 `@OnCommitted` 处理函数接收的命令才会要求回执，所以其他命令不需要为此付出任何代价。发往另一个上下文的命令随身带着这个请求，目标在命令提交后通过 `wow.<sender>.receipts` 发回回执。回执带着命令的结果，而不是目标的状态。

目标未作改变就接受的命令（`Decision.NoOp`）也会收到回执，版本不变。`@OnCommitted` 处理函数与 `@OnRejected` 处理函数一样，接收 `ReceiptContext`：它派生命令，不能定时，也不能同步调用。

## 处理拒绝 {#handle-a-rejection}

派生的命令可能被拒绝：库存不足，订单在此期间已被取消。拒绝是业务结果，所以 Saga 在针对该命令类型的 `@OnRejected` 处理函数中处理它，处理函数接收 `CommandRejected<C>`，并返回用来补偿的命令：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#rejected -->

```kotlin
/** A product the inventory cannot reserve cancels the order that asked for it. */
@OnRejected
fun onReserveRejected(rejected: CommandRejected<ReserveStock>): CommandRequest<CancelOrder> =
    CommandRequest.of(
        CancelOrder("${rejected.ref.id} cannot be reserved: ${rejected.error.message}"),
        rejected.source.id,
    )
```

`CommandRejected` 写明了命令（`commandId`、`commandType`、`ref`）、它的 `error`（错误码、消息、violations、扩展）Saga 的 `executionId`，以及 `source`，即派生出命令的提交所属的聚合；它不带命令体。来自另一个上下文的拒绝以回执的形式返回。运行时把每个拒绝只交出一次。

每条派生命令各自结算：一条命令被拒绝不会阻止其他命令，每个拒绝都交给它的处理函数。发往同一个聚合的命令按 Saga 派生的顺序执行；发往不同聚合的命令并发执行，所以依赖另一条命令结果的工作不要与它一起派生：从第一条命令提交的事件中派生它。Saga 没有处理函数的拒绝，会像失败一样在 Saga 的订阅中停放这个聚合，只停放一次，并列出每条被拒绝的命令。不需要补偿的拒绝，用一个返回 `Unit` 的处理函数接受：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#release-rejected -->

```kotlin
/**
 * A release the inventory rejects needs no compensation: it rejects one only for a product it has no stock of,
 * which reserved nothing either. Without this handler, the rejection would park the order for the saga; returning
 * `Unit` accepts it and derives nothing.
 */
@Suppress("UnusedParameter")
@OnRejected
fun onReleaseRejected(rejected: CommandRejected<ReleaseReservation>) = Unit
```

`CommandRejected<*>` 和 `CommandCommitted<*>` 接收 Saga 派生的每一条命令。

## 同步调用另一个上下文 {#call-another-context-synchronously}

默认情况下，Saga 派生命令，由命令主题投递它们。当目标在线本来就是前提、Saga 又需要立即拿到结果时，处理函数改用 `context.call` 调用目标的命令网关：

<!-- sample: examples/eshop/basket/basket-domain/src/main/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/BasketCheckoutSaga.kt#call -->

```kotlin
@OnEvent(calls = [SubmitOrder::class])
suspend fun onBasketCheckedOut(event: StateTypedEvent<BasketState, BasketCheckedOut>, context: SagaContext) {
    val checkedOut = event.event
    context.call(
        CommandRequest.of(
            SubmitOrder(checkedOut.items.map { OrderItem(it.productId, it.unitPrice, it.quantity) }),
            checkedOut.orderId,
        ),
    )
}
```

- 处理函数在 `@OnEvent(calls = [...])` 里声明它调用的类。Manifest 列出它们，等待会跟随这次调用，`call` 拒绝其他任何类。
- `call` 接收一个 `CommandRequest`，像返回的请求一样派生，在命令于目标上提交后返回它的结果：确定的请求 ID 让每次重试都是同一个请求，目标只决定一次。调用等待的是提交，所以它的请求不带 `wait` 或 `timeout`。
- 拒绝会抛出 `CommandRejectedException`，这是业务结果，不会重试。失败或没有得到结果的调用抛出 `WowProblemException`，可以用同一个请求 ID 重试：处理函数失败并重试，之后停放这个聚合。它们就是 `CommandClient` 抛出的异常。
- 节点通过目标的内部端口访问它：按目标契约声明的服务经 DNS 找到它的节点，或者使用 `wow.peers.<context>.url`（[配置对端](./calling-services#configure-a-peer-over-http)）。处理函数调用的上下文无法到达时，节点不会启动。Saga 自己上下文的命令总是派生，从不调用。
- `call` 是一个 `suspend` 函数，所以用 Java 写的 Saga 改为派生它的命令。

## 定时命令 {#schedule-a-command}

`context.schedule(key, rule, request)` 要求请求的目标在 `key` 下为自己定时它的命令；`context.cancel(key, target)` 取消它。两者都记录到上下文中：运行时在处理函数返回的命令之后、按调用顺序发送它们。处理函数要在 `@OnEvent(schedules = [...])` 里声明被定时命令的完整类型名。聚合也可以在自己的决定中用 `DecideScope` 定时命令：见[编写聚合](./aggregates#ask-the-kernel-for-more)。

## 从哪里开始 {#where-it-starts}

Saga 的订阅在某个分区还没有检查点时，从它的流的末尾开始，所以部署它不会再对历史采取行动；读取另一个上下文事件流的订阅则从开头开始。`@OnEvent(start = ...)` 可以覆盖它，宿主的 `wow.subscriptions.[<processor.function>].start` 也可以。有了第一个检查点之后，订阅就从那里继续。

## 测试 {#test-it}

`sagaSpec` 把已提交的事件、回执和拒绝交给 Saga，并检查它派生的命令：见[测试领域代码](./testing#sagas)。

## 下一步 {#next}

- [发布与消费事件流](./published-streams)：在 Saga 中读取另一个上下文的事件。
