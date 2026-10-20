---
title: 测试 V10 服务
description: Wow V10 的 wow-test 中的 aggregateSpec、sagaSpec、projectionSpec 与 reactorSpec 测试 DSL、绑定索引测试与服务端测试。
outline: deep
---

# 测试 V10 服务

## 测试 DSL 在哪里

测试 DSL 随 V10 模块 **`wow-test`** 发布，包名为 `me.ahoo.wow.test`：

```kotlin
dependencies {
    testImplementation("me.ahoo.wow:wow-test:$wowVersion")
    // 在 Wow 仓库内：testImplementation(project(":test-dsl"))
}
```

它沿用 9.x `wow-test` 模块的构件名，但不沿用它的 API：它不是 9.x 那种要继承的 `AggregateSpec<A, S>` 类，它是一组由 JUnit 测试调用的函数，聚合用 `aggregateSpec`，处理器用 `sagaSpec`、`projectionSpec` 与 `reactorSpec`。

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
| `givenState(state, version = 1, scope = Scope.NONE)` | 让聚合从这个版本的这个状态开始，如同它的前 `version` 条流把它留在那里；必须是规格的第一步 |
| `givenState(version = 1, scope) { … }` | 同上，状态是被块修改过的聚合初始状态 |
| `whenCommand(command, requestId, expectedVersion, scope) { … }` | 以 `scope` 中的调用方把命令体送入内核，并检查结果 |
| `advanceTime(duration) { … }` | 拨动时钟，并把此时已到期的、聚合给自己定时的命令依次发给它；块检查最后一条，没有命令到期时失败。不带块时从不失败 |
| `expectNothingDue(within)` | 把时钟拨动 `within`，到那时若有聚合给自己定时的命令到期就失败（例如已取消的定时）；不发送任何命令 |
| `ref(name)` | 标记当前位置，供之后的 `fork(from = name)` 使用 |
| `fork(name, from = null) { … }` | 在聚合的副本上运行块：副本是聚合现在的样子，或在标记 `from` 处的样子 |
| `now`、`ref`、`timer(key)` | 规格当前的时间、聚合，以及它持有的某个定时器 |

时间停在 `start`，直到 `advanceTime` 推进它：提交时间与定时命令的到期时间都从它读取。

### 断言

| 断言 | 通过条件 |
| --- | --- |
| `expectEvents(vararg events)` | 命令恰好按顺序提交了这些事件 |
| `expectEventTypes(vararg types)` | 恰好按顺序提交了这些类的事件，类可写作 `KClass`（`OrderPaid::class`）或 `Class` |
| `expectEvent<E> { … }` | 恰好提交了一个事件，是 `E`，且块中的检查在它上面成立 |
| `expectUnchanged()` | 被接受且未改变任何东西（`Decision.NoOp`） |
| `expectRejected(code) { … }` | 以这个错误码被拒绝；可选的块检查 `Rejection`，例如它的 `message` |
| `expectError<T> { … }` | 决定函数抛出了 `T`（例如 `require` 不成立）；可选的块检查这个异常 |
| `expectNoError()` | 决定函数没有抛出异常，命令也没有被拒绝 |
| `expectState { … }` | 在提交事件之后的状态上，块中的检查成立 |
| `expectScope(scope)` | 提交的流让聚合处于这个作用域 |
| `expectScheduled(key, command, at, after, target)` | 在 `key` 下定时了这条命令 |
| `expectScheduleCancelled(key)` | 取消了定时 `key` |
| `outcome`、`error` | 原始的 `CommandOutcome`（`Committed`、`Unchanged`、`Rejected` 等），用于断言覆盖不到的内容，例如 `(outcome as CommandOutcome.Committed).results`；以及决定函数抛出的异常 |

命令没有提交任何东西（`expectUnchanged()`、`expectRejected(…)` 或 `expectError<…>()`）之后，`expectState` 检查聚合当前所处的状态。

拒绝是一个值（`Decision.reject`），按错误码检查；决定函数抛出的异常是失败，按类型检查。没有被 `expectError` 检查的异常会原样抛出，让规格失败。

内核检查决定过程从不修改状态：给状态赋值的命令函数或 after 钩子会让规格失败，即使事件是对的。

### 从某个状态开始

`givenState` 让聚合从一段长历史会留下的状态开始，而不重放那段历史（即 9.x 的 `givenState`）。之后的命令基于这个状态决定，它们的流从它的版本接着编号：

```kotlin
@Test
fun `a payment given as succeeded should be refunded`() = aggregateSpec(model) {
    givenState(version = 2) {
        onSourcing(PaymentRequested(order, amount))
        onSourcing(PaymentSucceeded(transaction))
    }
    whenCommand(RefundPayment("refused"), expectedVersion = 2) { expectEvents(PaymentRefunded("refused")) }
}
```

状态像快照一样经由它的 JSON 复制：它必须能序列化，之后再修改那个对象不会影响规格。

### 分叉

共享前缀的场景从前缀分叉，而不是把前缀重复一遍。每个分叉运行在聚合自己的副本上（它的日志、定时器与时钟），所以分叉里做的事只留在分叉里，规格从原来的位置继续。`ref(name)` 标记一个之后可以分叉的位置：

```kotlin
@Test
fun `a requested payment should end confirmed or failed`() = aggregateSpec(model) {
    whenCommand(RequestPayment(order, amount)) { expectEventTypes(PaymentRequested::class) }
    ref("requested")
    fork("confirmed") {
        whenCommand(ConfirmPayment(transaction)) { expectEventTypes(PaymentSucceeded::class) }
    }
    fork("failed") {
        whenCommand(FailPayment("declined")) { expectEventTypes(PaymentFailed::class) }
        whenCommand(ConfirmPayment(transaction)) {
            expectRejected(PaymentRejections.WRONG_STATUS)
            expectState { status.assert().isEqualTo(PaymentStatus.FAILED) }
        }
    }
    fork(from = "requested") { /* … */ }
}
```

分叉里的失败会写明分叉名：`In fork "failed": Expected rejection …`。分叉可以嵌套，名字依次连接（`outer > inner`）。

### 定时命令

```kotlin
@Test
fun `an unpaid order should cancel itself`() = aggregateSpec(model) {
    whenCommand(placeOrder) { expectScheduled("pay-timeout", CancelOrder("unpaid"), after = 30.minutes) }
    advanceTime(30.minutes) { expectEvents(OrderCancelled("unpaid")) }
}

@Test
fun `a paid order should not cancel itself`() = aggregateSpec(model) {
    whenCommand(placeOrder) { }
    whenCommand(payOrder) { expectScheduleCancelled("pay-timeout") }
    expectNothingDue(within = 30.minutes)
}
```

## 测试 Saga、投影与反应器

处理器通过编译器为它生成的处理函数绑定来测试：每个 `@Saga`、`@Projection` 或 `@Reactor` 类，在它的包里都有一个函数 `<processor>Handlers(dependencies)`（`OrderSaga` 对应 `orderSagaHandlers`）。它只构造这个处理器，并从 `dependencies` 取它自己的服务，所以测试不需要模块里其他处理器所需的服务。

### `sagaSpec`

```kotlin
class OrderSagaTest {
    private val order = AggregateType("example", "order").ref("tenant", "order-1")
    private val handlers = orderSagaHandlers(Dependencies.NONE)

    @Test
    fun `a placed order should reserve its stock`() = sagaSpec(handlers) {
        whenEvent(stateEvent(order, OrderState("order-1"), orderCreated)) {
            expectCommands(ReserveStock("order-1", 2), ReserveStock("order-1", 1))
            // 再次处理同一次提交，派生出相同的请求。
            expectDeterministic()
        }
    }

    @Test
    fun `a reservation the inventory refused should cancel the order`() = sagaSpec(handlers) {
        whenEvent(stateEvent(order, OrderState("order-1"), orderCreated)) {
            whenRejected(commands.last(), "inventory.stock.insufficient", "Only 0 of pear are available.") {
                expectCommand(CancelOrder("pear cannot be reserved: Only 0 of pear are available."), to = order)
            }
        }
    }
}
```

| 调用 | 作用 |
| --- | --- |
| `whenEvent(event, handler = null) { … }` | 像各自的订阅那样，把这次提交交给 Saga 中读取它的每个处理函数：在 `aggregates` 中列出其聚合类型的，或未列出聚合类型而接受其状态类型的。`handler` 指定只调用其中一个（`"onOrderCreated"`） |
| `whenRejected(command, code, message, commandType) { … }` | 把本规格派生的某条命令的拒绝交给 Saga 中处理它的 `@OnRejected` 函数，与目标拒绝该命令时运行时的做法相同 |
| `whenRejected(rejected, CommandClass::class) { … }` | 同上，用于手工构造的 `CommandRejected` |

每次调用处理函数都得到一个新的 `ExecutionSagaContext`，与运行时每次处理一次提交时一样，所以同一次提交每次调用派生出的 ID 都相同。自己构造上下文的测试要每次调用构造一个：上下文会给它派生的命令编号，用同一个上下文调用第二次会派生出别的 ID。

| 断言 | 通过条件 |
| --- | --- |
| `expectCommands(vararg bodies)` | 处理函数恰好按顺序派生了这些命令体的命令 |
| `expectCommandTypes(vararg types)` | 恰好按顺序派生了这些类的命令 |
| `expectCommand(body, to = null)` | 恰好派生了一条命令，命令体是它，给出 `to` 时发往 `to`；返回这条命令 |
| `expectCommand<C> { … }` | 恰好派生了一条命令，是 `C`；块检查这条 `Command<C>`（它的 `ref`、`body`、`causation`） |
| `expectNoCommand()` | 没有派生任何命令 |
| `expectScheduled(key, command, at, after, target)` | 在 `key` 下定时了这条命令；`after` 从提交时间算起 |
| `expectScheduleCancelled(key, target)`、`expectNoSchedule()` | 取消了定时 `key`；没有改变任何定时 |
| `expectHandledBy(vararg functions)` | 恰好按顺序调用了这些处理函数 |
| `expectDeterministic()` | 用新的上下文再次处理这次提交或这次拒绝，派生出相同的命令（包括 ID）与相同的定时 |
| `expectError<T> { … }`、`expectNoError()` | 某个处理函数抛出了 `T`；没有处理函数抛出异常 |
| `commands`、`schedules`、`handled`、`error` | 原始的命令、定时（`SagaSchedule`）、处理函数名与异常 |

`stateEvent(ref, state, vararg events, version = 1, …)` 构造这次提交；其命令的 ID 默认为 `command-<version>` 与 `request-<version>`，提交时间默认为 `DEFAULT_START`，`commandType`、`scope`、`causation`、`headers` 与 `operator` 可以按名字给出。读取其他上下文所发布的流的 Saga，按名字得到那个上下文的事件：用 `publishedEvent(name, body, revision)` 构造它们，状态用 Saga 对它的视图：

```kotlin
whenEvent(stateEvent(order, OrderView(total), publishedEvent("eshop-ordering.order.OrderStockConfirmed"))) {
    expectCommand(RequestPayment("alice-1", total), to = payment)
}
```

### `projectionSpec` 与 `reactorSpec`

投影或反应器写入一个端口（仓储、邮件服务），测试替换它为假实现：把假实现交给生成的处理函数绑定，喂给它们提交，再检查假实现中的内容。

```kotlin
@Test
fun `a placed order should show in the order list`() {
    val views = InMemoryOrderViews()
    projectionSpec(orderListProjectionHandlers(Dependencies.of(views))) {
        whenEvent(stateEvent(order, state, orderCreated)) {
            expectHandledBy("onOrder")
            views["order-1"]!!.status.assert().isEqualTo("CREATED")
        }
    }
}
```

`reactorSpec` 对反应器做同样的事。`whenEvent`、`expectHandledBy`、`expectError` 与 `expectNoError` 的用法与 Saga 相同。

### 上下文本身

`ExecutionSagaContext`、`ExecutionProjectContext` 与 `ExecutionReactContext`（`me.ahoo.wow.kernel`）就是运行时提供给处理函数的上下文，`rejectionContextOf` 给出 `@OnRejected` 处理函数的上下文。直接调用处理器函数的测试自己构造它们，每次调用一个。

## 测试绑定索引与片段

领域模块的测试可以加载编译器生成的内容：`ModelProvider.load()` 找到模块的绑定索引，`provider.models(Dependencies.of(stubs…))` 构建它的模型，`provider.fragment` 给出要读取并检查的片段资源（路由、事件、错误码、订阅）。`provider.processors(dependencies)` 构造模块的每个处理器，所以需要它们全部的服务。eShop 订单上下文的 [`OrderingDomainModelProviderTest`](https://github.com/Ahoo-Wang/Wow/blob/v10/example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/OrderingDomainModelProviderTest.kt) 就是这样做的。

## 测试服务端

以随机端口在内存后端上启动应用，通过 HTTP 调用它：

```kotlin
val context = SpringApplicationBuilder(CatalogServer::class.java, TestCallers::class.java)
    .run("--server.port=0", "--wow.internal.port=0", "--wow.internal.host=127.0.0.1", "--wow.backend=memory")
```

- 给测试一个读取测试请求头的 `CallerResolver` Bean，让每个请求都有所有者（示例的 `HeaderCallers` 读取 `X-User`）。
- 内存后端上，除非 `wow.query.memory.enabled` 为 `true`，没有查询后端提供快照查询，它们的路由返回 404。
- Saga 向其他上下文发送命令的服务，没有命令传输就无法启动：给测试提供容器中的 Kafka，或内存主题（见[向其他上下文发送命令](./programming-model.md#向其他上下文发送命令)）。
- 调低 `wow.cluster.drain-timeout`、`wow.subscription.stop-timeout` 与 `wow.wait.max-timeout`，让上下文更快关闭。
- 针对 PostgreSQL、MongoDB、Kafka 或 ClickHouse 的测试在容器中运行（Testcontainers），放在模块的 `integrationTest` 源集里。
