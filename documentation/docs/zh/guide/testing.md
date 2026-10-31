---
title: 测试领域代码
description: 用 wow-test DSL 测试聚合、Saga、投影、反应器和定时任务：用写模型时的语言来写，不需要启动服务。
outline: deep
---

# 测试领域代码

领域代码用写它时的语言来测试：已经发生了什么，来了一条什么命令，期望得到这些事件和这个状态。`wow-test` DSL 在真实的内核和内存日志上运行你的聚合，通过编译器为处理器生成的绑定运行你的处理器。领域测试不需要服务、数据库或 broker。本页说明怎样测试每一类领域代码。

## 准备 {#set-up}

把制品 `me.ahoo.wow:wow-test` 加进测试依赖；它的版本来自 Wow BOM。这个 DSL 是包 `me.ahoo.wow.test` 中一组由 JUnit 测试调用的函数：聚合用 `aggregateSpec`；处理器用 `sagaSpec`、`projectionSpec` 和 `reactorSpec`；定时任务用 `scheduleSpec`。每一个都有一个以 `Tests` 结尾的孪生函数，把同样的规格变成一棵测试树。通过查询 API 读取内存快照的处理器规格还需要 `me.ahoo.wow:wow-test-query`（[读取](#reads)）。

JUnit 测试函数必须返回 `Unit`。这些规格函数返回 `Unit`，所以 `fun test() = aggregateSpec(...)` 没有问题；`Tests` 孪生函数返回测试树，供 `@TestFactory` 使用。

从 9.x 的 `AggregateSpec` 和 `SagaSpec` 迁来：[迁移源码](../migrate/source#tests)列出每个 9.x 写法在 V10 中的对应形式，并且连续的 `whenCommand` 现在是链接的。

## 聚合 {#aggregates}

快速开始里的银行账户，这样测试：

<!-- sample: examples/quickstart/src/test/kotlin/me/ahoo/wow/example/quickstart/AccountSpec.kt -->

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

`accountModel()` 是编译器为 `Account` 聚合生成的模型工厂。工厂接收上下文的名字（默认为模块所在的上下文），以及命令函数需要的领域服务；传入它们的桩。`aggregateSpec` 运行一个聚合，即 `tenantId` 中的 `id`（默认是默认租户 `(0)` 中的 `aggregate-1`；给了 `caller` 时是它的租户）。

### 准备与执行 {#arrange-and-act}

| 步骤 | 作用 |
| --- | --- |
| `given(vararg events, scope = null, operator = null, commitTime = now)` | 把这些事件作为一条流提交，就像之前的某条命令产生了它们；`scope` 让聚合处于该所有者和空间。`operator` 和 `commitTime` 是演化函数读到的提交事实（[读取提交](./aggregates#read-the-commit)）。 |
| `givenState(state, version, scope)`、`givenState(version, scope) { … }` | 让聚合从某个版本的某个状态开始，不重放历史；块形式修改初始状态。必须是规格的第一步。 |
| `givenRecords(vararg records, scope, operator, commitTime)` | 把存储的记录按日志中的样子作为一条流提交：模型的类已经写不出的历史，例如聚合已退役的事件（内核会跳过它的记录），或者旧修订版的记录（经它的升级读取）。 |
| `givenReservation(namespace, key, owner)` | 把一个预留键交给同一类型的另一个聚合，于是预留它的命令会被拒绝。 |
| `givenCommands(vararg commands)` | 把这些命令作为场景的准备发出；被拒绝或抛出异常的命令会让规格失败，并指出是哪一条。 |
| `whenCommand(command, requestId, expectedVersion, caller, scope, …) { … }` | 把命令送进内核，并在块中检查它的结果。`caller`（默认为 `aggregateSpec(model, caller = …)` 给出的调用方）像命令网关那样，给出命令的作用域、操作人和应用；`scope` 指定调用方去往的另一个作用域。 |
| `asCaller(caller) { … }` | 以另一个调用方发出块中的命令。 |
| `advanceTime(duration) { … }` | 拨动时钟，并把聚合给自己定时、到那时已到期的每条命令发给它；块检查最后一条。 |
| `expectNothingDue(within)` | 拨动时钟；到那时若有聚合给自己定时的命令到期，就失败。 |
| `ref(name)`、`fork(name, from) { … }` | 标记一个位置；在聚合的副本上运行一个场景，从现在或从标记的位置开始。 |
| `holds(namespace, key)` | 聚合是否持有某个预留键。 |
| `now`、`timer(key)` | 规格当前所处的时间；聚合在某个定时键下保存的定时器，无论处于什么状态，没有时为 null。 |

时间停在 `start`（默认 2026-01-01T00:00:00Z），直到 `advanceTime` 推进它：决定把它读作 `scope.now`，它的提交也记录在这个时间。决定函数拿到的 ID 是顺序的：`id-1`、`id-2`，依此类推。

### 断言 {#expect}

| 断言 | 通过条件 |
| --- | --- |
| `expectEvents(vararg events)` | 命令恰好按顺序提交了这些事件。 |
| `expectEventTypes(vararg types)` | 恰好按顺序提交了这些类的事件。 |
| `expectEvent<E> { … }` | 恰好提交了一个事件，它是 `E`，且块中的检查在它上面成立。 |
| `expectEventEnvelope<E> { … }` | 同上，并在事件（`body`）旁给出事件流的信封：`aggregateId`、`tenantId`、`version`、`commandId`、`requestId`、`headers`。 |
| `expectEventCount(n)` | 恰好提交了 `n` 个事件。 |
| `expectUnchanged()` | 被接受，且没有改变任何东西。 |
| `expectVersion(n)` | 命令之后聚合处于版本 `n`：已提交事件流的版本，或命令什么都没追加时聚合停留的版本。 |
| `expectDeleted()` | 命令提交了聚合的删除。 |
| `expectRejected(code) { … }` | 以这个错误码被拒绝，无论是返回的还是抛出的（`reject`、`require`、`check`）；块检查这个拒绝。 |
| `expectError<T> { … }` | 决定函数抛出了 `T`。 |
| `expectNoError()` | 没有抛出异常，命令也没有被拒绝。 |
| `expectState { … }` | 块中的检查在命令之后的状态上成立。 |
| `expectState(expected)` | 命令之后的状态逐字段等于 `expected`；不一致时列出每一处差异。 |
| `expectResult(key, value)`、`results` | 命令已提交，且决定函数为调用方放入了这个值（`putResult`）；`results` 读取全部结果，命令什么都没提交时读取它会失败，因为只有已提交的命令才带着结果回答。 |
| `expectScope(scope)`、`expectTags(tags)` | 命令提交的事件流使聚合处于这个作用域（没有提交的命令会使它失败），或者命令之后聚合恰好带有这些标签。 |
| `expectScheduled(key, command, rule)`、`expectScheduleCancelled(key)` | 在 `key` 下定时了这条命令，或取消了这个定时。 |
| `fork(name) { … }`、`fork(name, model) { … }` | 从这条命令的结果分叉（[测试树](#a-tree-of-tests)）。 |

`error` 是决定函数抛出的异常，返回了决定时为 null。失败的检查会结束这个块，所以它之后的分叉不会运行。

拒绝是一个值，按错误码检查。带事件的拒绝会提交这些事件，所以 `expectRejected` 可与 `expectEvents`、`expectVersion` 和 `expectState` 一起检查它们以及它们留下的状态；带事件被拒绝的创建命令会让测试失败并指出该命令，因为它的事件永远无法提交。决定函数抛出的异常是失败，按类型检查；没有被任何 `expectError` 检查的异常，会像被抛出时那样让测试失败。命令什么都没提交时，`expectState` 检查的是聚合当前所处的状态。

**决定函数不能修改状态。** 在规格中，内核比较每个命令函数和 after 钩子前后的状态，若其中某个直接修改了状态，测试失败。不经过事件的修改在运行时会与日志渐渐不一致；测试会把它抓出来。

### 从某个状态开始 {#start-from-a-state}

当一个场景从一段很长的历史会留下的状态开始时，直接从那个状态开始，不必重放历史。之后的命令基于它做决定，它们的流从它的版本接着往下：

<!-- sample: examples/eshop/payment/payment-domain/src/test/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentSpec.kt#given-state -->

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

<!-- sample: examples/eshop/payment/payment-domain/src/test/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentSpec.kt#fork -->

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

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSpec.kt#fork-model -->

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

<!-- sample: examples/eshop/payment/payment-domain/src/test/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/PaymentSpec.kt#tree -->

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

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSpec.kt#schedule -->

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

处理器通过编译器为它生成的处理函数来测试：对每个 `@Saga`、`@Projection` 或 `@Reactor` 类，在它的包里有一个函数 `<processor>Handlers(services…)`。它只构建这一个处理器，使用测试传入的服务（`orderSummaryProjectionHandlers(summaries = …)`；不需要服务的处理器什么也不传）。

`sagaSpec` 像订阅那样，把一次提交交给读取它的 Saga 处理函数，并检查它们发出的命令。`givenAggregate(model, id, vararg history)` 给出聚合：由它的模型从历史推出状态和版本，每个事件一次提交，都提交于 2026-01-01T00:00:00Z。`whenEvent(events, by = Command::class)` 交出它的下一次提交，命令类型按模型的命名。对端的状态是它 api 生成的视图（`OrderStateView`），模块里没有模型来溯源它，仍用 `stateEvent(ref, state, vararg events, version, …)` 手工构建，`OrderPaymentSagaTest` 就是这样做的。订单流程的测试给出模块自己的订单：

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSagaTest.kt#saga -->

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

`expectDeterministic()` 再处理一次这次提交，像运行时那样给每次处理一个新的上下文，并检查 Saga 发出相同的命令、带相同的 ID。正是这一点让重新投递的提交是安全的：目标对每个请求只决定一次。投影和反应器没有这项检查：再处理一次提交会让它们的副作用再执行一次。

### 步骤 {#steps}

`sagaSpec`、`projectionSpec` 和 `reactorSpec` 的步骤相同。每个 `when…` 步骤的检查块都是可选的：没有块时，步骤只在处理函数抛出异常时失败。

| 步骤 | 作用 |
| --- | --- |
| `givenAggregate(model, id, vararg history, tenantId, scope)` | 给出聚合，`whenEvent` 交出的是它之后的提交；它处于其历史留下的状态和版本。分叉推进的是自己的副本。 |
| `whenEvent(vararg events, on, by, headers, operator, handler) { … }` | 在给出的聚合（`on`，默认是唯一的那个）上提交这些事件，并把这次提交交给读取它的处理函数。`by` 是提交这些事件的命令类，它像模型那样为提交给出命令类型；`handler = "onX"` 只调用那个处理函数。 |
| `whenEvent(stateEvent, handler) { … }` | 交出一个手工构建的提交（[对端状态与发布的事件流](#peer-states-and-published-streams)）。 |
| `whenRedelivered(on, handler) { … }` | 把最近一次提交再交一次，就像对每次提交至少投递一次的订阅在重试或重新分配之后所做的那样。幂等的处理函数会再做一遍同样的事，或什么都不做。 |
| `whenDeliveredLate(version, on, handler) { … }` | 在较新的提交之后再交出一次较旧的提交，就像另一个分区的滞后或一次重放投递它那样。保留最新版本的处理函数会留下后面那些提交写下的内容。 |
| `whenRejected(command, code, message) { … }`、`whenCommitted(command, version, aggregateId, duplicate) { … }` | 把规格派生的一条命令的拒绝或成功回执交回给 Saga（[拒绝与回执](#rejections-and-receipts)）。 |
| `answerCalls { … }` | 回答 Saga 的同步调用（[同步调用](#synchronous-calls)）。 |
| `givenQueries(queries)` | 为处理函数的读取提供服务（[读取](#reads)）；没有它时，读取的处理函数会失败。 |
| `fork(name) { … }` | 在规格当前状态的副本上运行一个场景；在某个步骤的检查块里，则在那次处理留下的副本上运行。在 `sagaSpecTests` 中，每个步骤是一个测试，每个分叉是一个容器。 |

### 提交会到达哪些处理函数 {#which-handlers-a-commit-reaches}

提交会像订阅那样，到达读取它的每个处理函数：在 `aggregates` 中指明了提交的聚合类型的处理函数，或者没有指明、但接收它的状态类型的处理函数。只处理一种事件类型的处理函数（例如 `StateTypedEvent<S, E>`）会像运行时那样，跳过不含该类型事件的提交，所以每个处理函数都跳过的提交不会被任何处理函数处理，这可以用不带名字的 `expectHandledBy()` 检查。完全没有处理函数读取的提交会让规格失败：这是测试的错误，不是跳过。

### 拒绝与回执 {#rejections-and-receipts}

Saga 发出的命令可能被目标拒绝，或者带回执地提交。`whenRejected` 和 `whenCommitted` 像运行时那样，把这个结果交回给 Saga 的 `@OnRejected` 或 `@OnCommitted` 处理函数。拒绝码须是目标可能给出的：它的契约生成的常量，或 `FrameworkCodes` 中的码；其他码会失败：

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSagaTest.kt#rejected -->

```kotlin
@Test
fun `a reservation the inventory refused should cancel the order`() = sagaSpec(handlers) {
    val order = givenAggregate(model, ORDER, OrderSubmitted(items))
    whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) {
        val cap = commands.single { it.ref.id == "cap" }
        whenRejected(cap, EshopInventoryContract.Stock.Errors.INSUFFICIENT, "Only 0 of cap are available, not 1.") {
            expectHandledBy("onReserveRejected")
            expectCommand(
                CancelOrder("cap cannot be reserved: Only 0 of cap are available, not 1."),
                to = order.ref
            )
            // The same receipt again derives the same request.
            expectDeterministic()
        }
    }
}
```

### 同步调用 {#synchronous-calls}

同步调用另一个服务的 Saga，从 `answerCalls` 拿到回答，就像目标的命令网关会给出的那样：

- `committedResult(command)`，默认值，在版本 1 提交；
- `rejectedResult(command, code)`，处理函数拿到的是 `CommandRejectedException`；
- `failedResult(command, code)`，处理函数拿到的是 `WowProblemException`；
- 从块中抛出的异常，就像宕机的目标抛出的那样。

处理函数放行的拒绝交给 Saga 中这条命令的 `@OnRejected` 处理函数，被中止的处理留下的是它的命令。结账 Saga 没有这样的处理函数，所以运行时立即停放这个购物篮，规格用 `expectParked` 说明这一点。`expectCalls` 和 `calls` 检查它调用了什么：

<!-- sample: examples/eshop/basket/basket-domain/src/test/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/BasketCheckoutSagaTest.kt#calls -->

```kotlin
@Test
fun `an order the ordering context refuses should park the checkout`() = sagaSpec(handlers) {
    answerCalls { rejectedResult(it, EshopOrderingContract.Order.Errors.WRONG_STATUS) }
    basket()
    whenEvent(checkedOut, by = CheckoutBasket::class, operator = alice) {
        // The saga lets the call's rejection through: a business outcome, so the runtime parks the basket at once.
        val rejected = expectParked().cause as CommandRejectedException
        rejected.code.assert().isEqualTo(EshopOrderingContract.Order.Errors.WRONG_STATUS)
    }
}
```

### 读取 {#reads}

处理函数通过查询 API 读取的 Saga，从 `givenQueries` 拿到读到的内容：读侧的一个假实现，或 `wow-test-query` 提供的、基于内存快照的读侧。这个读侧是类路径上各模块声明的 Manifest 的查询引擎：处理函数的过滤条件、作用域与租户策略以及预算都像在节点上一样生效。`givenSnapshots(model, id, *events)` 持有聚合在这些事件之后所处状态的快照，版本是最后一个事件的版本：由它的模型像 `givenAggregate` 那样重放这些事件，模型没有溯源函数的事件会被拒绝。`givenSnapshots(type, id to state)` 按给定的样子持有状态，用于测试中没有模型溯源的状态。手头有模型时，优先用事件：写成 JSON map 的状态绕过了状态的 schema，拼错的字段会被悄悄丢掉，读回的状态里该字段是默认值。读取多个聚合的规格，给一个 `givenQueries(memoryQueries { snapshot(model, id, *events); … })`，其中也可以用 `snapshots(type, id to state, …)`；每次 `givenSnapshots` 都会替换之前给出的查询。

### 失败、重试与停放 {#failures-retries-and-parking}

抛出异常的处理函数会让规格失败，除非这一步的检查预期了它：`expectError<T>`、`expectRetried` 或 `expectParked`。规格像运行时的订阅那样，判断运行时会如何处理这次失败：

- 处理中遇到的拒绝会立即停放聚合，不重试：处理函数放行的同步调用拒绝，或者没有 `@OnRejected` 处理函数接收的派生命令的拒绝（[拒绝与回执](#rejections-and-receipts)）。拒绝是业务结果，重试改变不了它。
- 其他失败要先分类：先看处理函数的 `@Retry(recoverable, unrecoverable)`，再看类路径上各模块的失败分类器，最后是内置规则（[失败如何分类](./failures#how-a-failure-is-classified)）。不可恢复的失败立即停放；宕机或施加背压的下游会重试，且不消耗预算；其余失败会一直重试，直到这次处理用完 `@Retry(maxAttempts)`，默认是 5 次。
- 运行时间超过 `@Retry(timeout)` 的处理会被取消，算作一次失败的尝试。这个时间是真实时间，不是规格的时钟。

`expectRetried(attempt)` 在运行时会于这次尝试之后重试处理时通过，默认是第一次尝试；`expectParked(attempt)` 在运行时会于那时停放它时通过。两者都返回抛出的异常。规格不等待尝试之间的退避，也不使用停放后的重试和应用自己的 `FailureClassifier` Bean：在分类器旁边测试它们。[测试失败](./failures#test-it)给出了一个先被重试、再收到同一次提交的反应器。

### Saga 的断言 {#saga-expectations}

| 断言 | 通过条件 |
| --- | --- |
| `expectCommands(vararg bodies)`、`expectCommandTypes(vararg types)` | 处理函数恰好按顺序发出了这些命令体或这些类的命令。 |
| `expectCommand(body, to)`、`expectCommand<C> { … }` | 恰好发出了一条命令，命令体是这个、发往这个聚合；或者是一个 `C`，块检查它的命令体。 |
| `expectCommandEnvelope<C> { … }` | 恰好发出了一条命令，它是 `C`，块连同信封一起检查它：在 `body` 旁给出它发往的聚合（`ref`）、它的各个 ID、作用域、请求事实和操作人。 |
| `expectCommandCount(n)` | 恰好发出了 `n` 条命令。 |
| `expectNoCommand()` | 什么都没发。 |
| `expectCalls(vararg bodies)`、`expectNoCall()` | 恰好同步调用了这些命令，或者一个都没调用。 |
| `expectScheduled(…)`、`expectScheduleCancelled(…)`、`expectNoSchedule()` | 定时或取消了一条命令，或者没有改变任何定时。 |
| `expectHandledBy(vararg functions)` | 恰好按顺序调用了这些处理函数。 |
| `expectDeterministic()` | 再处理一次这次提交或这个结果，发出相同的命令，包括 ID。只用于 Saga。 |
| `expectError<T> { … }`、`expectNoError()` | 某个处理函数抛出了 `T`；没有处理函数抛出异常。 |
| `expectRetried(attempt)`、`expectParked(attempt)` | 处理失败了，运行时会在这次尝试之后重试或停放它（[失败、重试与停放](#failures-retries-and-parking)）。 |

`commands`、`calls`、`schedules`、`handled` 和 `error` 给出原始结果，用于表里没有覆盖的检查。

### 对端状态与发布的事件流 {#peer-states-and-published-streams}

对端的状态没有本模块的模型溯源，它是用 `stateEvent(ref, state, vararg events, version, …)` 手工构建的提交，就像 `OrderPaymentSagaTest` 那样。读取另一个上下文发布的事件流的 Saga，拿到的是那个上下文的事件；当对方上下文的类不在测试的类路径上时，用 `publishedEvent(name, body, revision)` 按名字构建一个。`rejectedReceipt` 和 `committedReceipt` 为另一个处理函数派生的命令构建回执，交给接收回执的 `whenRejected` 和 `whenCommitted`。

## 投影与反应器 {#projections-and-reactors}

投影或反应器写入一个端口，例如仓储或邮件服务，测试为它提供假实现。把假实现传给处理器生成的处理函数，用 `whenEvent` 喂给它们提交，然后检查假实现里有什么。`projectionSpec` 和 `reactorSpec` 的步骤与 `sagaSpec` 相同，断言只有适用于它们的那些：`expectHandledBy`、`expectError`、`expectNoError`、`expectRetried` 和 `expectParked`。

订阅对每次提交至少投递一次，另一个分区的提交也可能在较新的提交之后才到达。`whenRedelivered()` 把最近一次提交再交一次，用来测试处理函数是否幂等；`whenDeliveredLate(version)` 在较新的提交之后交出一次较旧的提交，用来测试读模型是否保留最新的版本。[测试投影](./projections#test-them)给出了 `whenRedelivered`。

## 定时任务 {#scheduled-jobs}

Saga 的 `@OnSchedule` 处理函数用 `scheduleSpec(handlers, start)` 测试，针对的是真实的任务聚合，运行在生成的处理函数所指明的上下文中。时间停在 `start`，直到 `advanceTime` 推进它。

| 步骤 | 作用 |
| --- | --- |
| `enable(job, tenantId, rule)`、`reschedule(job, tenantId, rule)`、`disable(job, tenantId)` | 按默认规则或另一条规则启用任务的一个实例，就像 `wow.schedule.Enable` 那样；覆盖它的规则；停用它。`SHARDED` 任务的实例还要给出它的 `partition`。 |
| `advanceTime(duration) { … }` | 推进时间：每个实例运行那时到期的时间槽，它的任务的处理函数收到每次触发。块用 Saga 的断言，一起检查处理函数对所有触发做了什么；没有到期的时间槽时块会失败；没有块时，只有处理函数或任务命令抛出异常才会失败。 |
| `whenTick(tick) { … }` | 不经任务聚合，直接把一次触发交给它的任务的处理函数。 |
| `ticks`、`now`、`instanceOf(job, tenantId)` | 至今交出的触发、当前时间，以及一个实例的状态。 |
| `answerCalls { … }`、`givenQueries(queries)` | 与 Saga 规格中相同。 |

一次越过多个时间槽时只运行最近的那个，其余的计入触发的 `skipped`，除非任务的规则要求补跑。失败的触发按它的任务的 `@Retry` 重试或停放，见[失败、重试与停放](#failures-retries-and-parking)。`scheduleSpecTests` 让每个带检查的 `advanceTime` 和每个 `whenTick` 各成为一个测试。[测试定时任务](./scheduling#test-it)给出了一个任务早晨的触发。

## 编译器生成了什么 {#what-the-compiler-generated}

领域模块里的测试可以加载编译器为它生成的东西，检查模块的绑定索引和 Manifest 片段：它指明哪个上下文、构建哪些模型、它的处理器变成哪些订阅。

<!-- sample: examples/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/OrderingDomainModelProviderTest.kt#provider -->

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
- 在内存后端上，快照查询和事件查询默认从内存提供，所以测试可以查询它写下的内容；`wow.query.memory.enabled: false` 关闭它们。它们运行的是内存引擎，不是真实存储的引擎。
- Saga 向另一个上下文发命令的服务需要命令传输：容器里的 Kafka，或者 `wow.transport=memory`。
- 针对 PostgreSQL、MongoDB、Kafka 或 ClickHouse 的测试在容器里运行，放在模块的 `integrationTest` 测试套件中；Gradle 插件 `me.ahoo.wow` 和 `me.ahoo.wow.service` 会注册它（JUnit Jupiter、模块的类以及 `testImplementation` 提供的一切），`check` 不会运行它：源码放在 `src/integrationTest/kotlin`，用 `./gradlew integrationTest` 运行。
