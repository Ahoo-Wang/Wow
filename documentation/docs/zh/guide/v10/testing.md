---
title: 测试 V10 服务
description: Wow V10 内核 test fixtures 中的 aggregateSpec 测试 DSL、Saga 与处理器测试、绑定索引测试与服务端测试。
outline: deep
---

# 测试 V10 服务

## 测试 DSL 在哪里

聚合测试 DSL 随**内核的 test fixtures** 发布，包名为 `me.ahoo.wow.kernel.test`：

```kotlin
dependencies {
    testImplementation(testFixtures("me.ahoo.wow:wow-kernel:$wowVersion"))
    // 在 Wow 仓库内：testImplementation(testFixtures(project(":kernel")))
}
```

它不是 9.x 的 `wow-test` 模块，API 也不是 9.x 那种要继承的 `AggregateSpec<A, S>` 类：它是一个由 JUnit 测试调用的函数 `aggregateSpec`。

## `aggregateSpec`

```kotlin
fun <S : Any> aggregateSpec(
    model: AggregateModel<S>,
    id: String = "aggregate-1",
    tenantId: String = "tenant",
    start: Instant = DEFAULT_START, // 2026-01-01T00:00:00Z
    block: AggregateSpec<S>.() -> Unit,
)
```

它在真实内核与内存日志上为一个聚合（`model.type.ref(tenantId, id)`）运行模型。用编译器生成的工厂构建模型，传入上下文与桩服务：

```kotlin
class OrderSpec {
    private val model = orderModel("example", StubPricingService(mapOf("apple" to BigDecimal("2.50"))))

    @Test
    fun `a paid order should refuse a new address`() = aggregateSpec(model, id = "order-1") {
        given(orderCreated)
        given(OrderPaid(total, paid = true))
        whenCommand(ChangeAddress(office)) { expectRejected(OrderRejections.WRONG_STATUS) }
    }
}
```

JUnit 测试函数必须返回 `Unit`。`aggregateSpec` 返回 `Unit`，所以 `fun test() = aggregateSpec(...)` 没问题；表达式体返回其他值的测试会被静默跳过。

### 准备与执行

| 调用 | 作用 |
| --- | --- |
| `given(vararg events, scope = null)` | 把这些事件作为一条流提交，如同之前的命令产生了它们；`scope` 让聚合处于该所有者与空间 |
| `whenCommand(command, requestId, expectedVersion, scope) { … }` | 以 `scope` 中的调用方把命令体送入内核，并检查结果 |
| `advanceTime(duration) { … }` | 拨动时钟，并把此时已到期的、聚合给自己定时的命令依次发给它；块检查最后一条 |
| `now`、`ref`、`timer(key)` | 规格当前的时间、聚合，以及它持有的某个定时器 |

时间停在 `start`，直到 `advanceTime` 推进它：提交时间与定时命令的到期时间都从它读取。

### 断言

| 断言 | 通过条件 |
| --- | --- |
| `expectEvents(vararg events)` | 命令恰好按顺序提交了这些事件 |
| `expectEventTypes(vararg types)` | 恰好按顺序提交了这些类的事件 |
| `expectUnchanged()` | 被接受且未改变任何东西（`Decision.NoOp`） |
| `expectRejected(code)` | 以这个错误码被拒绝 |
| `expectState { … }` | 在提交事件之后的状态上，块中的检查成立 |
| `expectScope(scope)` | 提交的流让聚合处于这个作用域 |
| `expectScheduled(key, command, at, after, target)` | 在 `key` 下定时了这条命令 |
| `expectScheduleCancelled(key)` | 取消了定时 `key` |
| `outcome` | 原始的 `CommandOutcome`（`Committed`、`Unchanged`、`Rejected` 等），用于断言覆盖不到的内容，例如 `(outcome as CommandOutcome.Committed).results` |

`expectState` 需要已提交的事件：`expectUnchanged()` 之后没有可读的已提交状态。

内核检查决定过程从不修改状态：给状态赋值的命令函数或 after 钩子会让规格失败，即使事件是对的。

### 定时命令

```kotlin
@Test
fun `an unpaid order should cancel itself`() = aggregateSpec(model) {
    whenCommand(placeOrder) { expectScheduled("pay-timeout", CancelOrder("unpaid"), after = 30.minutes) }
    advanceTime(30.minutes) { expectEvents(OrderCancelled("unpaid")) }
}
```

## 测试 Saga、投影与反应器

处理器是普通的类：用一个 `StateEvent` 和内核为一次执行提供的上下文调用它的处理函数。

```kotlin
private fun event(events: List<Any>) = StateEvent(
    ref = AggregateType("example", "order").ref("tenant", "order-1"),
    version = 1,
    commandId = "command-1",
    requestId = "request-1",
    commandType = "example.order.CreateOrder",
    commitTime = 1L,
    events = events,
    state = OrderState("order-1"),
)

@Test
fun `a placed order should reserve its stock`() {
    val event = event(listOf(orderCreated))
    val context = ExecutionSagaContext(ExecutionId("OrderSaga.onOrderCreated", event.ref, event.version), event)
    val commands = OrderSaga().onOrderCreated(event, context)
    // 再次处理同一次提交，派生出相同的请求。
    OrderSaga().onOrderCreated(event, context).map { it.requestId }.assert().isEqualTo(commands.map { it.requestId })
}
```

`ExecutionSagaContext`、`ExecutionProjectContext` 与 `ExecutionReactContext`（`me.ahoo.wow.kernel`）就是运行时提供给处理函数的上下文。测试 `@OnRejected` 处理函数时，从生成的索引取得它的绑定，并使用 `rejectionContextOf`：

```kotlin
val saga = ModelProvider.load().single().processors(Dependencies.NONE).single { it.name == "OrderSaga.onOrderCreated" }
val onRejected = saga.rejectionHandlerOf(ReserveStock::class.java)!!
val compensation = onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected))
```

## 测试绑定索引与片段

领域模块的测试可以加载编译器生成的内容：`ModelProvider.load()` 找到模块的绑定索引，`provider.models(Dependencies.of(stubs…))` 构建它的模型，`provider.fragment` 给出要读取并检查的片段资源（路由、事件、错误码、订阅）。示例的 [`ShopDomainModelProviderTest`](https://github.com/Ahoo-Wang/Wow/tree/v10/example/shop/shop-domain/src/test/kotlin/me/ahoo/wow/example/shop/domain/ShopDomainModelProviderTest.kt) 就是这样做的。

## 测试服务端

以随机端口在内存后端上启动应用，通过 HTTP 调用它：

```kotlin
val context = SpringApplicationBuilder(CatalogServer::class.java, TestCallers::class.java)
    .run("--server.port=0", "--wow.internal.port=0", "--wow.internal.host=127.0.0.1", "--wow.backend=memory")
```

- 给测试一个读取测试请求头的 `CallerResolver` Bean，让每个请求都有所有者（示例的 `HeaderCallers` 读取 `X-User`）。
- 内存后端上没有查询后端提供快照查询，它们的路由返回 404。要查询快照的测试，把内存参考后端放到类路径上：`testImplementation(testFixtures("me.ahoo.wow:wow-query:$wowVersion"))`。
- 调低 `wow.cluster.drain-timeout`、`wow.subscription.stop-timeout` 与 `wow.wait.max-timeout`，让上下文更快关闭。
- 针对 PostgreSQL、MongoDB、Kafka 或 ClickHouse 的测试在容器中运行（Testcontainers），放在模块的 `integrationTest` 源集里。
