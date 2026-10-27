---
title: 发布与消费事件流
description: 选择聚合向其他上下文发布什么，并在 Saga、投影或反应器中用对端自己的事件类读取另一个上下文发布的事件流。
outline: deep
---

# 发布与消费事件流

上下文通过发布它的聚合的事件流，分享它内部发生的事情。其他上下文像读自己的事件流一样读它们：处理函数指明聚合，接收对端的事件类，并在自己的检查点里记录位置。本页发布 ordering 的订单，并在 payment 上下文中读取它。它与上下文之间其他通信方式的比较，见[上下文之间](../concepts/cross-context)。

## 上下文发布什么 {#what-a-context-publishes}

每个聚合默认发布它的状态事件：每次提交一条记录，带着这次提交的事件和提交之后的状态。Manifest 把它们列为上下文的 `published` 事件流，上下文的 Kafka Sink 把它们写入主题 `wow.<context>.<aggregate>.state`。`@AggregateRoot` 可以按聚合改变这一点：

| 设置 | 效果 |
| --- | --- |
| `published = false` | 不发布这个聚合 |
| `publishState = false` | 发布它的事件，但不带状态，用于状态太大不便发送的情况 |
| `topic = "…"` | 另一个主题名 |

ordering 原样发布它的订单，主题是 `wow.eshop-ordering.order.state`。发布的事件流是上下文契约的一部分：它的事件和状态的字段就是对端读取的东西，Manifest 的兼容检查把删除字段或改变字段类型视为破坏性变更。

发布需要 Kafka：主题和信封见 [Kafka](../operate/kafka)。

## 读取另一个上下文的事件流 {#read-another-context-s-stream}

处理函数接收那个上下文 api 模块里的事件类，并声明它自己对状态的视图。服务的 Manifest 根据那个上下文的契约找出所属聚合；两个聚合共有的事件用 `@OnEvent(aggregates = [<Context>Contract.<Aggregate>.QUALIFIED_NAME])` 指明：

<!-- sample: example/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-stream -->

```kotlin
@OnEvent
fun onOrderStockConfirmed(
    event: StateTypedEvent<OrderView, OrderStockConfirmed>,
    context: SagaContext,
): Command<RequestPayment> = context.command(event.ref.id, RequestPayment(event.ref.id, event.state.totalAmount))
```

视图是订阅方的一个类，只包含它读取的字段：

<!-- sample: example/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#order-view -->

```kotlin
/**
 * The payment context's view of the ordering's order, decoded from the state the ordering context publishes: what
 * the order costs. Reading ignores the rest.
 */
data class OrderView(val totalAmount: BigDecimal = BigDecimal.ZERO)
```

- **事件类。** `OrderStockConfirmed` 是 ordering api 中的类。payment 的领域模块依赖 `eshop-ordering-api`，它的 jar 带着 ordering 的契约（`META-INF/wow/contract.json`）；不需要其他配置。
- **状态。** 发布的状态被解码成处理函数的视图，视图里没有的字段会被忽略。上游发布时不带状态时，把它声明为可空的 `StateEvent<OrderView?>`：对没有状态的事件流使用不可空的视图，节点启动时会失败。
- **聚合。** `aggregates` 中的所有聚合属于同一个上下文，写作 `context.aggregate`。
- **种类。** Saga、投影和反应器以同样的方式读取发布的事件流。

## 订阅方没有类的事件 {#events-the-subscriber-has-no-class-for}

接收 `StateTypedEvent<S, E>` 的处理函数，只会为是 `E` 的事件被调用。接收整个 `StateEvent<S>` 的处理函数，在事件的修订号就是 api 契约声明的那个修订号时，拿到的是 api 的类；否则拿到的是一个 `PublishedEvent`：它的 `name`（`eshop-ordering.order.OrderShipped`）、`revision` 和 JSON `payload`。所以针对旧版 api 构建的订阅方，在上游增加一个修订号后仍能继续工作，并自行决定怎样处理载荷。

处理函数接收的事件类，若类路径上的契约没有声明，节点不会启动。

## 从哪里开始 {#where-it-starts}

订阅另一个上下文事件流的订阅，在还没有检查点时从主题的开头开始，所以之后才加入的服务仍能拿到之前发布的内容。`@OnEvent(start = OnEvent.Start.END)` 或宿主的 `wow.subscriptions.[<processor.function>].start` 会让它改为从末尾开始。有了第一个检查点之后，它就从那里继续。

服务读取主题时不使用 Kafka 消费组：偏移量保存在它自己的检查点存储里，与它其他订阅的位置放在一起。

## 回应上游 {#answer-the-upstream}

对对端事件作出反应的 Saga，常常用一条发往那个对端的命令来回应：支付网关批准付款后，payment 为订单付款。

<!-- sample: example/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-command -->

```kotlin
@OnEvent
fun onPaymentSucceeded(
    event: StateTypedEvent<PaymentState, PaymentSucceeded>,
    context: SagaContext,
): Command<PayOrder> = context.command(event.state.orderId, PayOrder(event.ref.id, event.state.amount))
```

`context.command(id, body)` 从 ordering api jar 中的契约找到 ordering 的订单，命令发往 ordering 的命令主题 `wow.eshop-ordering.commands`。拒绝以回执的形式返回到 Saga 的 `@OnRejected` 处理函数：

<!-- sample: example/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-rejected -->

```kotlin
/** An order that refuses its payment has the payment refunded. */
@OnRejected
fun onPayRejected(rejected: CommandRejected<PayOrder>, context: ReceiptContext): Command<RefundPayment> =
    context.command(
        rejected.source.id,
        RefundPayment("The order ${rejected.ref.id} refused the payment: ${rejected.error.message}"),
    )
```

派生命令、回执和拒绝的完整说明，见[编写 Saga](./sagas)。

## 外部消费者 {#external-consumers}

不是 Wow 服务的系统读取同样的主题。每条记录都是一个以聚合为键的信封，所以一个聚合的记录保持顺序：见[线上协议](../reference/protocols#kafka-envelope)。

## 测试 {#test-it}

测试用测试 DSL 的 `stateEvent`，以 api 的事件类和订阅方的视图构建一次上游聚合的提交，交给处理函数：见[测试领域代码](./testing#sagas)。
