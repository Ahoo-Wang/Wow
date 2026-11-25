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

`accountModel()` 是编译器为 `Account` 聚合生成的模型工厂。工厂接收上下文的名字（默认为模块所在的上下文），以及命令函数需要的领域服务；传入它们的桩。每个服务是一个按其类型命名的参数，`PaymentPolicy` 对应 `paymentPolicy`，与命令函数给参数起的名字无关；同一类型的两个参数取的是该类型的同一个服务。同一类型的两个服务，用命令函数参数上的 `@DependencyName("…")` 区分：宿主给它那个名字的 bean，工厂也按这个名字命名它的参数，`@DependencyName("express") policy: PaymentPolicy` 就是 `paymentPolicyExpress`。处理器的处理函数工厂按构造函数的参数名命名它的服务。`aggregateSpec` 运行一个聚合，即 `tenantId` 中的 `id`（默认是默认租户 `(0)` 中的 `aggregate-1`；给了 `caller` 时是它的租户）。

### 准备与执行 {#arrange-and-act}

| 步骤 | 作用 |
| --- | --- |
| `given(vararg events, scope = null, operator = null, commitTime = now)` | 把这些事件作为一条流提交，就像之前的某条命令产生了它们；`scope` 让聚合处于该所有者和空间。`operator` 和 `commitTime` 是演化函数读到的提交事实（[读取提交](./aggregates#read-the-commit)）。 |
| `givenState(state, version, scope)`、`givenState(version, scope) { … }` | 让聚合从某个版本的某个状态开始，不重放历史；块形式修改初始状态。必须是规格的第一步。 |
| `givenRecords(vararg records, scope, operator, commitTime)` | 把存储的记录按日志中的样子作为一条流提交：模型的类已经写不出的历史，例如聚合已退役的事件（内核会跳过它的记录），或者旧修订版的记录（经它的升级读取）。 |
| `givenReservation(namespace, key, owner)` | 把一个预留键交给同一类型的另一个聚合，于是预留它的命令会被拒绝。 |
| `givenCommands(vararg commands)` | 把这些命令作为场景的准备发出；被拒绝或抛出异常的命令会让规格失败，并指出是哪一条。 |
| `whenCommand(command, requestId, expectedVersion, caller, scope, …) { … }` | 把命令送进内核，并在块中检查它的结果。`caller`（默认为 `aggregateSpec(model, caller = …)` 给出的调用方）像命令网关那样，给出命令的作用域、操作人和应用；`scope` 像 `Wow-Scope` 那样指定调用方去往的作用域。网关会拒绝的调用方让规格直接失败，并给出网关的答复：租户不是规格的租户的调用方（`400 wow.command.malformed`），或没有 `crossScope` 却指定了 `scope` 的调用方（`403 wow.auth.forbidden`）。没有调用方的命令属于匿名调用方，和没有调用方的请求一样，所以指定了 `scope` 的这种命令也同样让规格失败。请求 ID、聚合的 ID，或租户、所有者、空间的 ID 超过网关所接受的长度时，规格按网关自己的检查以 `400 wow.command.malformed` 失败。可以跨作用域的调用方在规格的租户和它指定的 `scope` 中行事。`aggregateSpec(model, fixedTenant = …)` 给出类型的固定租户（[固定的租户](./ownership#a-fixed-tenant)）：聚合在这个租户里，任何租户的调用方的命令都送到那里。`headers` 可以添加请求事实，但不能写 `app_id`，它只由调用方给出：`headers` 里写了它的规格直接失败，有没有调用方都一样，因为网关从不记录请求自己写的 `app_id`。 |
| `asCaller(caller) { … }` | 以另一个调用方发出块中的命令，与 `whenCommand` 的调用方一样经过准入：另一个租户的调用方让规格失败。 |
| `advanceTime(duration) { … }` | 拨动时钟，并把聚合给自己定时、到那时已到期的每条命令发给它；块检查最后一条。 |
| `expectNothingDue(within)` | 拨动时钟；到那时若有聚合给自己定时的命令到期，就失败。 |
| `ref(name)`、`fork(name, from) { … }` | 标记一个位置；在聚合的副本上运行一个场景，从现在或从标记的位置开始。 |
| `holds(namespace, key)` | 聚合是否持有某个预留键。 |
| `now`、`timer(key)` | 规格当前所处的时间；聚合在某个定时键下保存的定时器，无论处于什么状态，没有时为 null。 |

时间停在 `start`（默认 2026-01-01T00:00:00Z），直到 `advanceTime` 推进它：决定把它读作 `scope.now`，它的提交也记录在这个时间。决定函数拿到的 ID 是顺序的：`id-1`、`id-2`，依此类推。

只要服务为聚合注册了访问策略，规格就需要它：`aggregateSpec(model, caller = …, policy = documentAccess)`。这时内核像节点那样，按命令调用方的声明在策略下决定每条命令：没有调用方的命令属于匿名调用方，它不持有任何声明；服务调用方（`Caller.ofService(…)`）是受信的。声明了访问属性的模型总有策略，因为没有策略的节点不会启动（[所有权](./ownership#test-a-policy)）。没有 `policy` 时，由默认的作用域规则决定，和未注册策略的服务的节点一样。

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
| `expectScope(scope)`、`expectAccess(attributes)` | 命令提交的事件流使聚合处于这个作用域（没有提交的命令会使它失败），或者命令之后聚合的访问属性恰好是这些，按名字：单值属性为它的值或 null，多值属性为它的值，顺序不论。 |
| `expectScheduled(key, command, rule)`、`expectScheduleCancelled(key)` | 在 `key` 下定时了这条命令，或取消了这个定时。 |
| `fork(name) { … }`、`fork(name, model) { … }` | 从这条命令的结果分叉（[测试树](#a-tree-of-tests)）。 |

`error` 是决定函数抛出的异常，返回了决定时为 null。失败的检查会结束这个块，所以它之后的分叉不会运行。

拒绝是一个值，按错误码检查。带事件的拒绝会提交这些事件，所以 `expectRejected` 可与 `expectEvents`、`expectVersion` 和 `expectState` 一起检查它们以及它们留下的状态；带事件被拒绝的创建命令会让测试失败并指出该命令，因为它的事件永远无法提交。决定函数抛出的异常是失败，按类型检查；没有被任何 `expectError` 检查的异常，会像被抛出时那样让测试失败。命令什么都没提交时，`expectState` 检查的是聚合当前所处的状态。

**决定函数不能修改状态。** 在规格中，内核比较每个命令函数和 after 钩子前后的状态，若其中某个直接修改了状态，测试失败。不经过事件的修改在运行时会与日志渐渐不一致；测试会把它抓出来。

**状态是其事件的函数。** 每条提交了事件的命令之后，规格等 JVM 的时钟走过（不到一毫秒），再从它被给定的内容（事件，或给定的状态）以及此后的事件流重建状态。状态的 JSON 若不是命令留下的那个，测试失败，并指出每个不同的属性、两个值以及改法。所以无论在属性里还是在演化函数里，读取精确到毫秒或更细的时钟，或读取随机源，每次运行都会失败。读取日期或截到分钟的时间，以及与截止时间的比较，只在跨过各自的边界时才不同，而规格几乎不会跨过：detekt 规则拒绝这些（[不读时钟](./aggregates#read-no-clock)）。这项检查无法关闭：事件不能决定的状态，在重建它的各个节点上各不相同。

**块先看见自己的接收者。** DSL 的接收者标有 `@WowTestDsl`，一个 `@DslMarker`：在一个块里，不指明接收者就够不到外层块接收者的成员，所以处理器 `whenEvent { … }` 检查里的 `fork { … }` 看不到外层那个块的 `commands`。把分叉要用的值先绑定到一个 `val`，或者指明接收者（`this@whenEvent`）。Kotlin 还会先把名字解析到最近接收者的成员，再考虑测试类的属性：在 `expectEvent<E> { … }` 里块的接收者就是事件，所以一个名字既是 `E` 的字段又是测试类的属性时，它指的是事件的字段，把它与那个属性比较的断言就是拿字段和自己比较，必然通过。给期望值起一个不是事件字段的名字。

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

规格像节点那样发送并结算定时器：

- **触发的命令**带着为它定时的那次提交的操作人和作用域，且只带派生命令会继承的请求事实（`app_id`）。
- **决策抛出异常的发送**让定时器保持 `PENDING`，尝试次数加一，在退避之后到期，`advanceTime` 会在到期后再次发送。第十次失败的尝试让它变为 `PARKED`。
- **发给另一个上下文的聚合的命令**会送到那里，就像节点把它送到那个上下文的命令主题一样。它的定时器为 `FIRED`，结果为 `sent`，这里不会决策它。
- **发给同一上下文中另一个聚合的命令**会让规格按名称失败：节点在那个聚合上决策它，而一个聚合的规格做不到。用 `expectScheduled(…, target = …)` 检查这个定时，并在那个聚合自己的规格中检查这条命令。
- **用 `given(…)` 给定的流**若设置了定时，会像节点的 `timers` 订阅那样保留它的定时器，给定的删除会取消该聚合的定时器。

重试遵循节点的默认值，无论服务对 `wow.timers.max-attempts` 设置了什么。规格也跳过节点对触发的命令所做的准入：它的校验器、租户拒绝，以及为创建分配的 ID。

### 命令的约束 {#command-constraints}

规格运行命令的决策，不检查命令上的任何 Bean Validation 约束（`@NotBlank`、`@Positive` 等）：在节点上由准入层检查它们，并以 `wow.command.invalid` 回答其中的违规，服务器测试可以读到（[测试服务](./testing-services)）。规格也跳过准入层的下列检查，同样由服务器测试读到：

- 在字符串或键中写有 `\u0000` 的请求体；
- 名为 `Wow-…` 或 `wow.…` 的请求头，它们是保留的；
- 请求体的 JSON 往返，节点从 JSON 解码出命令；
- 没有调用方的命令所在的租户：规格把它送到规格自己的租户，而节点上的匿名调用方在默认租户中行事。

测试模块不带 Bean Validation 实现，而 9.x 的测试模块带有一个实现和它的 `TestValidator`。要在领域模块的测试中检查命令的约束，加入服务器运行的实现 Hibernate Validator，版本取服务器的 Spring Boot 所管理的版本，并像 9.x 的 `TestValidator` 那样构建校验器：

<!-- sample: fragment -->

```kotlin
// build.gradle.kts: testImplementation("org.hibernate.validator:hibernate-validator:<服务器的版本>")
private val validator = Validation.byDefaultProvider().configure()
    .messageInterpolator(ParameterMessageInterpolator())
    .buildValidatorFactory().validator

validator.validate(CancelOrder(reason = "")).map { "${it.propertyPath}" }.assert().containsExactly("reason")
```

`ParameterMessageInterpolator` 不需要表达式语言，9.x 的也不需要。服务器用 Spring Boot 的校验器插值消息，所以在文本可能不同的地方，断言违规的路径与约束。命令重复声明的约束（在字段上，以及在它所实现接口的 getter 上）是一个违规：Hibernate Validator 对每个属性的一个约束只报告一次。

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

`expectDeterministic()` 再处理一次这次提交，像运行时那样给每次处理一个新的上下文，并检查 Saga 发出相同的命令、带相同的 ID。正是这一点让重新投递的提交是安全的：目标对每个请求只决定一次。新的上下文像节点那样从 `context.ids` 生成新的 ID，所以命令体里带有生成 ID 的命令无法通过这项检查。用生成的 ID 指定目标的命令在第一次处理时就失败，与节点上一样（[构建命令](./sagas#build-the-commands)）。投影和反应器没有这项检查：再处理一次提交会让它们的副作用再执行一次。

### 步骤 {#steps}

`sagaSpec`、`projectionSpec` 和 `reactorSpec` 的步骤相同。每个 `when…` 步骤的检查块都是可选的：没有块时，步骤只在处理函数抛出异常时失败。

Saga 规格展示的是内核派生出的命令，每条都在它的请求指名的租户里，否则在执行的租户里。类型的固定租户（[固定的租户](./ownership#a-fixed-tenant)）由运行时在其后应用：发给固定在另一个租户的类型的命令会落到那个租户，若指名了别的租户则被拒绝。断言派生命令的租户时要知道这一点。

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

每个处理函数读到的是从 JSON 解码出的提交状态，各自一份，就像节点的视图交给它的那样：JSON 不保留的字段（`@JsonIgnore`、计算字段）不会被设置，一个处理函数对它的修改，其他处理函数读不到。它在所绑定的上下文中运行，这个上下文为它的执行命名，也就为它派生的每个 ID 命名：编译器为它生成的每个绑定给出模块的上下文，没有指明上下文的手写绑定在提交所在的上下文中运行。

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

和运行时一样：

- **回执带着它所属命令的作用域**，所以补偿在那个作用域中派生。
- **补偿自己的拒绝会停放。** `@OnRejected` 处理函数派生的命令，包括同步调用被拒绝时的补偿，被拒绝时不交给任何处理函数：补偿不会再被补偿。
- **另一个上下文的命令的回执没有处理函数的重试。** 它由那个上下文的回执订阅处理，所以 Saga 的 `@Retry` 只为它自己上下文的命令的回执分类。

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

处理函数通过查询 API 读取的 Saga，从 `givenQueries` 拿到读到的内容：读侧的一个假实现，或 `wow-test-query` 提供的、基于内存快照的读侧。这个读侧是类路径上各模块声明的 Manifest 的查询引擎：处理函数的过滤条件、作用域与租户策略以及预算都像在节点上一样生效。它准许 PostgreSQL 或 MongoDB 准许的查询，拒绝两者都拒绝的：按日期字符串（状态中的 `Instant`）比较、排序或分组，以及按两个数组排序，都会被拒绝（`unsupported-capability`、`parallel-array-sort`），所以规格不会放过节点会拒绝的读取。`givenSnapshots(model, id, *events)` 持有聚合在这些事件之后所处状态的快照，版本是最后一个事件的版本：由它的模型像 `givenAggregate` 那样重放这些事件，模型没有溯源函数的事件会被拒绝。`givenSnapshots(type, id to state)` 按给定的样子持有状态，用于测试中没有模型溯源的状态。手头有模型时，优先用事件：写成 JSON map 的状态绕过了状态的 schema，拼错的字段会被悄悄丢掉，读回的状态里该字段是默认值。读取多个聚合的规格，给一个 `givenQueries(memoryQueries { snapshot(model, id, *events); … })`，其中也可以用 `snapshots(type, id to state, …)`；每次 `givenSnapshots` 都会替换之前给出的查询。

状态声明了访问属性的聚合，像在节点上一样，按它的访问策略读取。没有策略时，读侧在规格读取这个聚合的地方拒绝，而不是在构建时拒绝：从不读取该聚合的规格照常运行，读取它的规格传入它所依据的策略，`memoryQueries(access = listOf(documentAccess)) { … }`。把策略声明为领域模块中的一个值，`val documentAccess = accessPolicy<DocumentState> { … }`，服务器的 `@Bean` 返回它，读取该聚合的规格也传入它，这样规格依据的是生产环境的策略，而不是一份副本。节点在启动时仍会拒绝声明了访问属性却没有策略的聚合。

### 失败、重试与停放 {#failures-retries-and-parking}

抛出异常的处理函数会让规格失败，除非这一步的检查预期了它：`expectError<T>`、`expectRetried` 或 `expectParked`。规格像运行时的订阅那样，判断运行时会如何处理这次失败：

- 处理中遇到的拒绝会立即停放聚合，不重试：处理函数放行的同步调用拒绝，或者没有 `@OnRejected` 处理函数接收的派生命令的拒绝（[拒绝与回执](#rejections-and-receipts)）。拒绝是业务结果，重试改变不了它。
- 其他失败要先分类：先看 Wow 自己的失败，再看处理函数的 `@Retry(recoverable, unrecoverable)`，然后看类路径上各模块的失败分类器，最后是内置规则（[失败如何分类](./failures#how-a-failure-is-classified)）。不可恢复的失败立即停放；宕机的下游会重试，且不消耗预算；施加背压的下游也一样，直到第二十次被推回的尝试停放该聚合，和节点默认的 `wow.subscription.backpressure.max-attempts` 一样；其余失败会一直重试，直到这次处理用完 `@Retry(maxAttempts)`，默认是 5 次。
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
| `enable(job, tenantId, rule)`、`reschedule(job, tenantId, rule)`、`disable(job, tenantId)` | 按默认规则或另一条规则启用任务的一个实例，就像 `wow.schedule.EnableJob` 那样；覆盖它的规则；停用它。`SHARDED` 任务的实例还要给出它的 `partition`。 |
| `advanceTime(duration) { … }` | 推进时间：每个实例运行那时到期的时间槽，它的任务的处理函数收到每次触发。块用 Saga 的断言，一起检查处理函数对所有触发做了什么；没有到期的时间槽时块会失败；没有块时，只有处理函数或任务命令抛出异常才会失败。 |
| `whenTick(tick) { … }` | 不经任务聚合，直接把一次触发交给它的任务的处理函数。 |
| `ticks`、`now`、`instanceOf(job, tenantId)` | 至今交出的触发、当前时间，以及一个实例的状态。 |
| `answerCalls { … }`、`givenQueries(queries)` | 与 Saga 规格中相同。 |

一次越过多个时间槽时只运行最近的那个，其余的计入触发的 `skipped`，除非任务的规则要求补跑。失败的触发按它的任务的 `@Retry` 重试或停放，见[失败、重试与停放](#failures-retries-and-parking)。`scheduleSpecTests` 让每个带检查的 `advanceTime` 和每个 `whenTick` 各成为一个测试。[测试定时任务](./scheduling#test-it)给出了一个任务早晨的触发。

## 服务路由 {#service-routes}

服务路由的处理函数像决策函数一样，直接调用来测试：用测试 DSL 构建的调用 `routeCall(caller, queries, commands, requestId)`。这个调用在调用方的租户与作用域中行事。省略时，调用方是匿名的，请求 ID 为 `request-1`；处理函数若读取或发送，测试就会失败并说明要提供什么。命令用 `answeringCommandClient { call -> … }`：它经 `send`、`wait` 或 `stream`、以 `of` 给出的任一调用方接收的每个请求，都交给这个 lambda，并记入 `calls`。lambda 用 `call.committed(version, results = …)`、`call.rejected(code, violations = …)` 或 `call.failed(code)` 应答，真实的客户端会返回或抛出它们（[按对端的方式答复](./testing-services#answer-as-the-peer-would)）。[测试路由](./service-routes#test-a-route)给出了店面的测试。`RouteCall` 与 `CommandClient` 由 Wow 实现：自己编写它们的测试需要 `@OptIn(InternalWowApi::class)`，并会在 Wow 新增成员时失效。

## 访问策略 {#access-policies}

访问策略用 `accessPolicySpec(model, policy)` 测试：给定事件之后、或某个状态和作用域下的聚合，`whenCaller(subject, claims, roles)` 像内核和查询那样，检查这个调用方能读取和修改什么。[测试策略](./ownership#test-a-policy)给出了一个例子。

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

生成的代码带有 `@WowGenerated`，所以覆盖率报告只度量你自己的代码：JaCoCo 自动跳过它，Kover 用 `annotatedBy` 排除它；JaCoCo 仍需要的两个名字模式见[编译器生成了什么](./generated-code#coverage)。

## 领域测试之外 {#beyond-domain-tests}

领域测试覆盖模型。服务测试启动应用并经 HTTP 调用它；只把它用在只有运行中的服务才能体现的东西上，例如路由、授权和配置：

- 在内存后端上运行，公开服务器和内部服务器使用随机端口，内部端口绑定在回环网卡上。
- 在开发 profile（`dev`）下启动，不给应用自己的调用方解析器：宿主于是从开发身份头读出每个请求的调用者，服务器的类型化客户端会按给它的调用者发送这些头（[以调用者身份发送与读取](./testing-services#send-and-read-as-a-caller)）。
- 在内存后端上，快照查询和事件查询默认从内存提供，所以测试可以查询它写下的内容；`wow.query.memory.enabled: false` 关闭它们。它们运行的是内存引擎，不是真实存储的引擎。
- Saga 向另一个上下文发命令的服务需要命令传输。开发 profile 中的测试服务器（`wowServer<App>("dev")`）不用配置就有，主题留在它的 JVM 里；针对 Kafka 的测试在容器里运行它。
- 针对 PostgreSQL、MongoDB、Kafka 或 ClickHouse 的测试在容器里运行，放在服务模块的 `integrationTest` 测试套件中；Gradle 插件 `me.ahoo.wow.service` 会注册它（JUnit Jupiter、模块的类以及 `testImplementation` 提供的一切），`check` 不会运行它：源码放在 `src/integrationTest/kotlin`，测试需要的依赖在套件里声明，用 `./gradlew integrationTest` 运行（[在真实存储上运行](./testing-services#run-it-on-real-stores)）。
