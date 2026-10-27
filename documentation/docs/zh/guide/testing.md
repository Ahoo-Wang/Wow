---
title: 测试领域代码
description: 用 wow-test DSL 测试聚合、Saga、投影、反应器和定时任务：用写模型时的语言来写，不需要启动服务。
outline: deep
---

# 测试领域代码

领域代码用写它时的语言来测试：已经发生了什么，来了一条什么命令，期望得到这些事件和这个状态。`wow-test` DSL 在真实的内核和内存日志上运行你的聚合，通过编译器为处理器生成的绑定运行你的处理器。领域测试不需要服务、数据库或 broker。本页说明怎样测试每一类领域代码。

## 准备 {#set-up}

把制品 `me.ahoo.wow:wow-test` 加进测试依赖；它的版本来自 Wow BOM。这个 DSL 是包 `me.ahoo.wow.test` 中一组由 JUnit 测试调用的函数：聚合用 `aggregateSpec`；处理器用 `sagaSpec`、`projectionSpec` 和 `reactorSpec`；定时任务用 `scheduleSpec`。`aggregateSpec`、`sagaSpec`、`projectionSpec` 和 `reactorSpec` 各有一个以 `Tests` 结尾的孪生函数，把同样的规格变成一棵测试树。

JUnit 测试函数必须返回 `Unit`。这些规格函数返回 `Unit`，所以 `fun test() = aggregateSpec(...)` 没有问题；`Tests` 孪生函数返回测试树，供 `@TestFactory` 使用。

从 9.x 的 `AggregateSpec` 和 `SagaSpec` 迁来：[迁移源码](../migrate/source#tests)列出每个 9.x 写法在 V10 中的对应形式，并且连续的 `whenCommand` 现在是链接的。

## 聚合 {#aggregates}

快速开始里的银行账户，这样测试：

<!-- sample: example/quickstart/src/test/kotlin/me/ahoo/wow/example/quickstart/AccountSpec.kt -->

```kotlin
package me.ahoo.wow.example.quickstart

import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

class AccountSpec {
    private val model = accountModel()

    @Test
    fun `opening an account names its owner`() = aggregateSpec(model, id = "alice") {
        whenCommand(OpenAccount("Alice")) {
            expectEvents(AccountOpened("Alice"))
            expectState { owner.assert().isEqualTo("Alice") }
        }
    }

    @Test
    fun `a deposit adds to the balance`() = aggregateSpec(model, id = "alice") {
        given(AccountOpened("Alice"))
        whenCommand(Deposit(100)) {
            expectEvents(Deposited(100))
            expectState { balance.assert().isEqualTo(100) }
        }
    }

    @Test
    fun `a withdrawal the balance does not cover is rejected`() = aggregateSpec(model, id = "alice") {
        given(AccountOpened("Alice"), Deposited(100))
        whenCommand(Withdraw(150)) { expectRejected(Account.Rejections.INSUFFICIENT_BALANCE) }
        whenCommand(Withdraw(30)) {
            expectEvents(Withdrawn(30))
            expectState { balance.assert().isEqualTo(70) }
        }
    }
}
```

`accountModel()` 是编译器为 `Account` 聚合生成的模型工厂。工厂接收上下文的名字（默认为模块所在的上下文），以及命令函数需要的领域服务；传入它们的桩。`aggregateSpec` 运行一个聚合，即 `tenantId` 中的 `id`（默认是 `tenant` 中的 `aggregate-1`）。

### 准备与执行 {#arrange-and-act}

| 步骤 | 作用 |
| --- | --- |
| `given(vararg events, scope = null)` | 把这些事件作为一条流提交，就像之前的某条命令产生了它们；`scope` 让聚合处于该所有者和空间。 |
| `givenState(state, version, scope)`、`givenState(version, scope) { … }` | 让聚合从某个版本的某个状态开始，不重放历史；块形式修改初始状态。必须是规格的第一步。 |
| `givenReservation(namespace, key, owner)` | 把一个预留键交给同一类型的另一个聚合，于是预留它的命令会被拒绝。 |
| `givenCommands(vararg commands)` | 把这些命令作为场景的准备发出；被拒绝或抛出异常的命令会让规格失败，并指出是哪一条。 |
| `whenCommand(command, requestId, expectedVersion, caller, scope, …) { … }` | 把命令送进内核，并在块中检查它的结果。`caller`（默认为 `aggregateSpec(model, caller = …)` 给出的调用方）像命令网关那样，给出命令的作用域、操作人和应用；`scope` 指定调用方去往的另一个作用域。 |
| `asCaller(caller) { … }` | 以另一个调用方发出块中的命令。 |
| `advanceTime(duration) { … }` | 拨动时钟，并把聚合给自己定时、到那时已到期的每条命令发给它；块检查最后一条。 |
| `expectNothingDue(within)` | 拨动时钟；到那时若有聚合给自己定时的命令到期，就失败。 |
| `ref(name)`、`fork(name, from) { … }` | 标记一个位置；在聚合的副本上运行一个场景，从现在或从标记的位置开始。 |
| `holds(namespace, key)` | 聚合是否持有某个预留键。 |

时间停在 `start`（默认 2026-01-01T00:00:00Z），直到 `advanceTime` 推进它。决定函数拿到的 ID 是顺序的：`id-1`、`id-2`，依此类推。

### 断言 {#expect}

| 断言 | 通过条件 |
| --- | --- |
| `expectEvents(vararg events)` | 命令恰好按顺序提交了这些事件。 |
| `expectEventTypes(vararg types)` | 恰好按顺序提交了这些类的事件。 |
| `expectEvent<E> { … }` | 恰好提交了一个事件，它是 `E`，且块中的检查在它上面成立。 |
| `expectEventEnvelope<E> { … }` | 同上，并在事件（`body`）旁给出事件流的信封：`aggregateId`、`tenantId`、`version`、`commandId`、`requestId`、`headers`。 |
| `expectEventCount(n)` | 恰好提交了 `n` 个事件。 |
| `expectUnchanged()` | 被接受，且没有改变任何东西。 |
| `expectRejected(code) { … }` | 以这个错误码被拒绝，无论是返回的还是抛出的（`reject`、`require`、`check`）；块检查这个拒绝。 |
| `expectError<T> { … }` | 决定函数抛出了 `T`。 |
| `expectNoError()` | 没有抛出异常，命令也没有被拒绝。 |
| `expectState { … }` | 块中的检查在命令之后的状态上成立。 |
| `expectState(expected)` | 命令之后的状态逐字段等于 `expected`；不一致时列出每一处差异。 |
| `expectResult(key, value)`、`results` | 命令已提交，且决定函数为调用方放入了这个值（`putResult`）；`results` 读取全部结果。 |
| `expectScope(scope)`、`expectTags(tags)` | 命令之后，聚合处于这个作用域，或者恰好带有这些标签。 |
| `expectScheduled(key, command, rule)`、`expectScheduleCancelled(key)` | 在 `key` 下定时了这条命令，或取消了这个定时。 |

拒绝是一个值，按错误码检查。决定函数抛出的异常是失败，按类型检查；没有被任何 `expectError` 检查的异常，会像被抛出时那样让测试失败。命令什么都没提交时，`expectState` 检查的是聚合当前所处的状态。

**决定函数不能修改状态。** 在规格中，内核比较每个命令函数和 after 钩子前后的状态，若其中某个直接修改了状态，测试失败。不经过事件的修改在运行时会与日志渐渐不一致；测试会把它抓出来。

### 从某个状态开始 {#start-from-a-state}

当一个场景从一段很长的历史会留下的状态开始时，直接从那个状态开始，不必重放历史。之后的命令基于它做决定，它们的流从它的版本接着往下：

<!-- sample: example/eshop/payment/payment-domain/src/test/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentSpec.kt#given-state -->

```kotlin
@Test
fun `a payment given as succeeded should be refunded`() = paymentSpec {
    givenState(version = 2) {
        onSourcing(PaymentRequested(ORDER, AMOUNT))
        onSourcing(PaymentSucceeded(TRANSACTION))
    }
    whenCommand(RefundPayment(REFUSED), expectedVersion = 2) {
        expectEvents(PaymentRefunded(REFUSED))
        expectState { transactionId.assert().isEqualTo(TRANSACTION) }
    }
}
```

状态像快照一样经 JSON 复制，所以它必须能序列化。

### 分叉开头相同的场景 {#fork-scenarios-that-share-a-beginning}

开头相同的场景从这个开头分叉，而不是重复它。每个分叉运行在聚合自己的副本上：它的日志、定时器、预留、ID 和时钟。分叉里做的事留在分叉里，分叉之前的步骤也不会再运行，所以决定函数之前调用过的领域服务不会再被调用。`ref` 标记一个之后可以从它分叉的位置：

<!-- sample: example/eshop/payment/payment-domain/src/test/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentSpec.kt#fork -->

```kotlin
@Test
fun `a requested payment should end confirmed and refunded, or failed`() = paymentSpec {
    givenCommands(RequestPayment(ORDER, AMOUNT))
    ref("requested")
    fork("confirmed") {
        givenCommands(ConfirmPayment(TRANSACTION))
        whenCommand(RefundPayment(REFUSED)) { expectEvents(PaymentRefunded(REFUSED)) }
    }
    fork("failed") {
        givenCommands(FailPayment(DECLINED))
        whenCommand(ConfirmPayment(TRANSACTION)) {
            expectRejected(Payment.Rejections.WRONG_STATUS)
            expectState { status.assert().isEqualTo(PaymentStatus.FAILED) }
        }
    }
    // The forks left the payment as requested.
    whenCommand(RefundPayment(REFUSED)) {
        expectRejected(Payment.Rejections.WRONG_STATUS)
        expectState { status.assert().isEqualTo(PaymentStatus.REQUESTED) }
    }
    fork(from = "requested") {
        whenCommand(FailPayment(DECLINED)) { expectEvents(PaymentFailed(DECLINED)) }
    }
}
```

fork 内部的失败会说明它所在的 fork，以及它检查的步骤和命令：`In fork "failed", whenCommand(ConfirmPayment) (command-4): …`。fork 可以嵌套，名字会连起来。在 fork 之外，失败的检查同样会说明它的步骤和命令；`expectEvents` 和 `expectState(expected)` 会按路径列出每一处差异（`[0].quantity: expected 3, was 2`），而不是打印两个完整的值。

fork 还可以换一套服务来运行：给它同一个模型、用编译器生成的工厂和另一个领域服务构建，这样一条分支就能看到服务给出不同的回答。聚合的副本由这个模型读取，它必须是同一个聚合类型：

<!-- sample: example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSpec.kt#fork-model -->

```kotlin
// One branch runs with another payment policy: the same model the compiler generated, built with other
// services, on a copy of the order so far.
fork("express", model = orderModel(paymentPolicy = PaymentPolicy(EXPRESS_GRACE))) {
    whenCommand(ConfirmOrderStock(CAP)) {
        expectScheduled(
            Order.PAYMENT_GRACE_PERIOD,
            CancelOrder(Order.PAYMENT_TIMEOUT),
            ScheduleRule.after(EXPRESS_GRACE),
        )
    }
}
```

### 测试树 {#a-tree-of-tests}

`aggregateSpecTests` 把同一种规格作为一棵测试树运行，供 JUnit 的 `@TestFactory` 使用：每条命令是一个测试，每个分叉是一个包含它所运行测试的容器。失败的检查只让它自己的测试失败；其他步骤和分叉照常运行，各自报告：

<!-- sample: example/eshop/payment/payment-domain/src/test/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentSpec.kt#tree -->

```kotlin
/**
 * The same lifecycle as a tree of tests: each command is a test and each fork a container, forked from the checks
 * of the request, so a failing branch is reported on its own and the others still run.
 */
@TestFactory
fun `a payment's lifecycle`() = aggregateSpecTests(paymentModel(), id = ORDER) {
    whenCommand(RequestPayment(ORDER, AMOUNT)) {
        expectEventTypes(PaymentRequested::class)
        fork("confirmed") {
            whenCommand(ConfirmPayment(TRANSACTION)) {
                expectEventTypes(PaymentSucceeded::class)
                fork("refunded") {
                    whenCommand(RefundPayment(REFUSED)) { expectEvents(PaymentRefunded(REFUSED)) }
                    whenCommand(ConfirmPayment(TRANSACTION)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
                }
            }
            whenCommand(FailPayment("too late")) { expectRejected(Payment.Rejections.WRONG_STATUS) }
        }
        fork("failed") {
            whenCommand(FailPayment(DECLINED)) { expectEvents(PaymentFailed(DECLINED)) }
            whenCommand(RefundPayment(REFUSED)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
        }
    }
    whenCommand(RefundPayment(REFUSED)) { expectRejected(Payment.Rejections.WRONG_STATUS) }
}
```

命令检查块里的分叉，从那条命令的结果分出去。

### 定时命令 {#scheduled-commands}

为自己的聚合定时一条命令的命令函数，靠拨动时钟来测试。`advanceTime` 像运行时的定时器那样，发送每条到期的命令：

<!-- sample: example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSpec.kt#schedule -->

```kotlin
@Test
fun `an unpaid order should cancel itself once its grace period has passed`() = orderSpec {
    given(submitted, OrderAwaitingValidation, OrderStockItemConfirmed(MUG))
    whenCommand(
        ConfirmOrderStock(CAP)
    ) { expectScheduled(Order.PAYMENT_GRACE_PERIOD, CancelOrder(Order.PAYMENT_TIMEOUT), ScheduleRule.after(GRACE)) }
    advanceTime(GRACE) {
        expectEvents(OrderCancelled(Order.PAYMENT_TIMEOUT))
        expectState {
            status.assert().isEqualTo(OrderStatus.CANCELLED)
            cancelReason.assert().isEqualTo(Order.PAYMENT_TIMEOUT)
        }
    }
}
```

cron 规则像运行时那样逐个时间槽推进：一次越过多个时间槽时只发送最近的那一个，除非规则要求补发。

## Saga {#sagas}

处理器通过编译器为它生成的处理函数来测试：对每个 `@Saga`、`@Projection` 或 `@Reactor` 类，在它的包里有一个函数 `<processor>Handlers(dependencies)`。它只构建这一个处理器，并从 `dependencies` 取它自己的服务。

`sagaSpec` 像订阅那样，把一次提交交给读取它的 Saga 处理函数，并检查它们发出的命令。`givenAggregate(model, id, vararg history)` 给出聚合：由它的模型从历史推出状态和版本，每个事件一次提交。`whenEvent(events, by = Command::class)` 交出它的下一次提交，命令类型按模型的命名。模块里没有模型来溯源的对端视图状态，仍用 `stateEvent(ref, state, vararg events, version, …)` 手工构建：

<!-- sample: example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSagaTest.kt#saga -->

```kotlin
@Test
fun `an order awaiting validation should reserve each product's stock in the inventory`() = sagaSpec(handlers) {
    // The order's model sources its state from its history; the commit after it is version 2.
    val order = givenAggregate(model, ORDER, OrderSubmitted(items))
    whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) {
        expectCommands(
            stock.ref(order.ref.tenantId, "mug") to ReserveStock(ORDER, 5),
            stock.ref(order.ref.tenantId, "cap") to ReserveStock(ORDER, 1),
        )
        // Handling the commit again derives the same requests, which the inventory decides once: the spec
        // gives each handling a fresh context, as the runtime does.
        expectDeterministic()
    }
}
```

`expectDeterministic()` 再处理一次这次提交，像运行时那样给每次处理一个新的上下文，并检查 Saga 发出相同的命令、带相同的 ID。正是这一点让重新投递的提交是安全的：目标对每个请求只决定一次。

### 拒绝与回执 {#rejections-and-receipts}

Saga 发出的命令可能被目标拒绝，或者带回执地提交。`whenRejected` 和 `whenCommitted` 像运行时那样，把这个结果交回给 Saga 的 `@OnRejected` 或 `@OnCommitted` 处理函数。拒绝码须是目标可能给出的：它的契约生成的常量，或 `FrameworkCodes` 中的码；其他码会失败：

<!-- sample: example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSagaTest.kt#rejected -->

```kotlin
@Test
fun `a reservation the inventory refused should cancel the order`() = sagaSpec(handlers) {
    val order = givenAggregate(model, ORDER, OrderSubmitted(items))
    whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) {
        val cap = commands.single { it.ref.id == "cap" }
        whenRejected(cap, EshopInventoryContract.Stock.Errors.INSUFFICIENT, "Only 0 of cap are available, not 1.") {
            expectHandledBy("onReserveRejected")
            expectCommand(CancelOrder("cap cannot be reserved: Only 0 of cap are available, not 1."), to = order.ref)
            // The same receipt again derives the same request.
            expectDeterministic()
        }
    }
}
```

### 同步调用 {#synchronous-calls}

同步调用另一个服务的 Saga，从 `answerCalls` 拿到回答：一个结果、一个拒绝，或者模拟服务宕机的异常。`expectCalls` 和 `calls` 检查它调用了什么：

<!-- sample: example/eshop/basket/basket-domain/src/test/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/BasketCheckoutSagaTest.kt#calls -->

```kotlin
@Test
fun `an order the ordering context refuses should reach the saga as a rejection`() = sagaSpec(handlers) {
    answerCalls { rejectedResult(it, EshopOrderingContract.Order.Errors.WRONG_STATUS) }
    basket()
    whenEvent(checkedOut, by = CheckoutBasket::class, operator = alice) {
        expectError<CallRejectedException> {
            code.assert().isEqualTo(
                EshopOrderingContract.Order.Errors.WRONG_STATUS
            )
        }
    }
}
```

处理函数通过查询 API 读取的 Saga，从 `givenQueries` 拿到读到的内容，这是读侧的一个假实现。

### Saga 的断言 {#saga-expectations}

| 断言 | 通过条件 |
| --- | --- |
| `expectCommands(vararg bodies)`、`expectCommandTypes(vararg types)` | 处理函数恰好按顺序发出了这些命令体或这些类的命令。 |
| `expectCommand(body, to)`、`expectCommand<C> { … }` | 恰好发出了一条命令，命令体是这个、发往这个聚合；或者是一个经块检查的 `C`。 |
| `expectNoCommand()` | 什么都没发。 |
| `expectCalls(vararg bodies)`、`expectNoCall()` | 恰好同步调用了这些命令，或者一个都没调用。 |
| `expectScheduled(…)`、`expectScheduleCancelled(…)`、`expectNoSchedule()` | 定时或取消了一条命令，或者没有改变任何定时。 |
| `expectHandledBy(vararg functions)` | 恰好按顺序调用了这些处理函数。 |
| `expectDeterministic()` | 再处理一次这次提交或这个结果，发出相同的命令，包括 ID。 |
| `expectError<T> { … }`、`expectNoError()` | 某个处理函数抛出了 `T`；没有处理函数抛出异常。 |

`commands`、`calls`、`schedules` 和 `handled` 给出原始结果，用于表里没有覆盖的检查。读取另一个上下文发布的事件流的 Saga，拿到的是那个上下文的事件；当对方上下文的类不在测试的类路径上时，用 `publishedEvent(name, body, revision)` 按名字构建一个。

## 投影与反应器 {#projections-and-reactors}

投影或反应器写入一个端口，例如仓储或邮件服务，测试为它提供假实现。把假实现传给处理器生成的处理函数，用 `whenEvent` 喂给它们提交，然后检查假实现里有什么。`projectionSpec` 和 `reactorSpec` 的步骤与 `sagaSpec` 相同，断言只有适用于它们的那些：`expectHandledBy`、`expectError` 和 `expectNoError`。把同一次提交喂两次，就能测试投影是否幂等。

## 定时任务 {#scheduled-jobs}

Saga 的 `@OnSchedule` 处理函数用 `scheduleSpec` 测试，针对的是真实的任务聚合。用 `enable` 启用任务的一个实例，用 `advanceTime` 拨动时钟，检查处理函数收到的触发和它们发出的命令。`whenTick` 直接交给它一次触发。

## 编译器生成了什么 {#what-the-compiler-generated}

领域模块里的测试可以加载编译器为它生成的东西，检查模块的绑定索引和 Manifest 片段：它指明哪个上下文、构建哪些模型、它的处理器变成哪些订阅。

<!-- sample: example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/OrderingDomainModelProviderTest.kt#provider -->

```kotlin
@Test
fun `the binding index should name the context and the fragment and build the order model`() {
    provider.context.assert().isEqualTo("eshop-ordering")
    provider.fragment.assert().isEqualTo("META-INF/wow/manifest/eshop-ordering-domain.json")
    provider.models(Dependencies.of(PaymentPolicy(30.minutes))).map { it.type.toString() }
        .assert().containsExactly("eshop-ordering.order")
    val ports = Dependencies.of(OrderSummaries { _, _ -> }, BuyerNotifications { _, _, _ -> })
    provider.processors(ports).map { it.name }.assert().containsExactlyInAnyOrder(
        "OrderProcessSaga.onOrderSubmitted",
        "OrderProcessSaga.onOrderAwaitingValidation",
        "OrderProcessSaga.onOrderCancelled",
        "OrderProcessSaga.onOrderShipped",
        "OrderSummaryProjection.onEvent",
        "ShipmentNotifier.onOrderShipped",
    )
}
```

`processors(dependencies)` 构建模块的所有处理器，所以需要它们全部的服务。只需要自己服务的处理器测试，改用它生成的处理函数。

## 领域测试之外 {#beyond-domain-tests}

领域测试覆盖模型。服务测试启动应用并经 HTTP 调用它；只把它用在只有运行中的服务才能体现的东西上，例如路由、授权和配置：

- 在内存后端上运行，公开服务器和内部服务器使用随机端口，内部端口绑定在回环网卡上。
- 给测试一个自己的调用方解析器，读取一个测试用的请求头，让每个请求都有租户和所有者。
- 在内存后端上，只有设置 `wow.query.memory.enabled: true` 时快照查询才会回答。
- Saga 向另一个上下文发命令的服务需要命令传输：容器里的 Kafka，或者 `wow.transport=memory`。
- 针对 PostgreSQL、MongoDB、Kafka 或 ClickHouse 的测试在容器里运行，放在模块的 `integrationTest` 源集中，`check` 不会运行它们。
