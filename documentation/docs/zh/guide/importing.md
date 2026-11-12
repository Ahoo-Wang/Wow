---
title: 从其他系统导入
description: 用一条声明创建时间的创建命令，把非 Wow 系统中的聚合导入服务；留下导入的时间，让重跑安全，并了解旧的时间在下游带来什么。
outline: deep
---

# 从其他系统导入

三年前在另一个商城系统里下的订单，导入之后仍应是三年前下的订单：它的 `firstEventTime` 是下单的时间，而不是导入运行的时间，也没有哪个领域字段在旁边给出第二个答案。导入用一条普通的创建命令做到这一点：它像其他命令一样经过网关，并声明聚合是何时创建的。本页以订单为例写一个导入。

9.x 的服务不这样导入：它的事件历史由 `migrate-data` 转换（[从 9.x 迁移](../migrate/)）。

## 编写导入命令 {#write-the-import-command}

<!-- sample: test/test-dsl/src/test/kotlin/me/ahoo/wow/test/OccurredAtSpecTest.kt#command -->

```kotlin
@CreateAggregate
@InternalCommand
data class ImportOrder(
    @AggregateId val legacyId: String,
    @OccurredAt val placedAt: Long,
    val importedAt: Long,
)

data class OrderImported(val importedAt: Long)
```

| 命令上的 | 为什么 |
| --- | --- |
| `@CreateAggregate` | 只有创建聚合的命令才声明它发生的时间。同一订单的第二次导入在读取时间之前就以 `wow.aggregate.exists` 应答 |
| `@InternalCommand` | 只有平台发送它（[只在内部受理的命令](./aggregates#keep-a-command-internal)）。在公开路由上声明时间的创建命令，会让任何能发送它的人选择其聚合的创建时间 |
| 源主键上的 `@AggregateId` | 订单的 ID 就是它在源系统中的主键，所以每次运行导入指向的都是同一个聚合（[在命令体中指明聚合](./aggregates#name-the-aggregate-in-the-body)） |
| `@OccurredAt` | 承载订单在源系统中创建时间的属性，纪元毫秒，`Long`，或为空即不声明的 `Long?` |
| `importedAt` | 订单进入 Wow 的时间：[留下导入的时间](#keep-when-it-was-imported) |
| 单独的事件 `OrderImported` | 对新订单起作用的 Saga 或反应器（比如对 `OrderSubmitted`）不会把导入的订单当作新订单：它不会为三年前的订单发送确认邮件 |

编译器检查这项声明：每条命令一个 `@OccurredAt` 属性，`Long` 或 `Long?`（不是 `Instant`），公开或 internal，由 Wow 的 JSON 写出，并且在创建聚合的命令上。Manifest 把它列为命令的 `occurredAtProperty`。给命令加上它是加法；去掉它，或把它移到另一个属性，是破坏性变更（[Manifest 与兼容](./manifest#the-compatibility-check)）。

## 声明的时间改变什么 {#what-the-declared-time-changes}

每次提交有两个时间：它记录的事发生的时间，即提交时间；以及它被写入的时间。几乎所有提交两者相同。在导入的创建上，内核把声明的时间作为提交时间，把时钟的读数作为写入的时间：

| 读发生的时间，即声明的时间 | 读写入的时间 |
| --- | --- |
| 决定函数的 `scope.now` | `after` 定时，以及 cron 规则的第一个时段：从写入算起（[定时命令](./scheduling)） |
| 提交时间，因此快照的 `firstEventTime`，以及下一次提交之前的 `lastEventTime` | 从某一时刻开始的订阅：在该时刻之后写入的导入会被读到 |
| 对快照和事件流的查询，以及数据集 | 订阅滞后指标 `wow.subscription.lag` |

`at` 定时指明了它的时刻，保持在那个时刻。导入之后的提交与其他提交一样，在时钟的时间。

内核拒绝晚于其时钟读数或早于纪元的声明时间，应答 `wow.command.invalid`（400），什么也不写入。为空即不声明：提交在时钟的时间。

## 留下导入的时间 {#keep-when-it-was-imported}

一旦提交时间成了下单的时间，系统里就没有任何东西记录它是何时导入的。所以导入事件留下它，这里是 `importedAt`：它是导入这件事的事实，就像源系统的 ID 或状态一样。

- 导入程序在命令体中给出它。一整批共用一次时钟读数即可。
- 决定函数把它复制进事件，演化函数从事件读取它。
- 它是导入程序的时钟。每次提交被写入的精确时刻在该提交的 `wow.commit.written-at` 头信息里，供审计。

决定函数不读自己的时钟：`scope.now` 就是声明的时间，而在那里读取的时钟每次重试都会变，不同节点之间也不同。

<!-- sample: fragment -->

```kotlin
import me.ahoo.wow.api.annotation.AggregateRoot

@AggregateRoot
class Order(private val state: OrderState) {
    fun onCommand(command: ImportOrder): OrderImported = OrderImported(command.importedAt)
}

class OrderState {
    var importedAt: Long? = null
        private set

    fun onSourcing(event: OrderImported) {
        importedAt = event.importedAt
    }
}
```

## 发送导入 {#send-the-import}

导入程序读取源系统，以它自己的服务调用方，通过命令客户端为每个聚合发送一条命令（[调用其他服务](./calling-services)）。在本服务中它在进程内发送；从另一个服务，它调用内部端口，由 `wow.callers.<service>.commands` 许可（[只在内部受理的命令](./aggregates#keep-a-command-internal)）。

<!-- sample: fragment -->

```kotlin
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest

val importer = commandClient.of(Caller.ofService("order-import"))
val importedAt = System.currentTimeMillis()
for (row in batch) {
    val command = ImportOrder(row.key, row.placedAt, importedAt)
    importer.wait(CommandRequest.of(command, requestId = "import:legacy-shop:${row.key}"))
}
```

- **请求 ID 由源主键派生。** 中途停下的运行从头再跑：请求 ID 已经提交过的命令被应答为已完成，`duplicate: true`，不会再决定一次。它的 `importedAt` 保持第一次运行的值。
- **不直接写入任何存储。** 每次导入都经过网关和内核：它的约束、唯一键、访问属性、流的操作者以及各订阅，都像看待任何创建一样看到它。直接写入日志或快照存储的行会绕过这一切。
- **所有者来自创建命令。** 属于所有者的聚合取导入程序的作用域，除非导入程序在源所有者的作用域中行事（[在另一个作用域中行事](./ownership#act-in-another-scope)）。

## 测试 {#test-it}

规格按导入声明的时间决定它：

<!-- sample: test/test-dsl/src/test/kotlin/me/ahoo/wow/test/OccurredAtSpecTest.kt#spec -->

```kotlin
aggregateSpec(model, id = "legacy-1", start = START) {
    whenCommand(ImportOrder("legacy-1", PLACED, IMPORTED)) {
        expectEventEnvelope<OrderImported> {
            body.assert().isEqualTo(OrderImported(IMPORTED))
            // The commit is at the time the order was placed; the header holds when it was written.
            commitTime.assert().isEqualTo(PLACED)
            headers[EventStreamRecord.WRITTEN_AT].assert().isEqualTo("${START.toEpochMilli()}")
        }
        expectState { importedAt.assert().isEqualTo(IMPORTED) }
    }
}
```

## 下游的旧时间 {#old-times-downstream}

- **投影和数据集** 若要显示导入的订单，除新订单的事件外也要处理 `OrderImported`。
- **导入设置的定时** 用 `after` 时，在导入写入之后那么久到期，而不是下单之后。用 `at` 且时刻已过的，立即到期。
- **带 TTL 的仓库历史**（`wow.aggregates.<context>.<aggregate>.clickhouse.history.ttl`）从提交时间算起。比 TTL 更旧的导入提交，写入后不久就离开仓库的历史。日志保留它：日志从不过期。

## 10.0 不做的 {#not-in-10-0}

- **只有创建声明时间。** 导入的聚合之后的命令在时钟的时间提交。编译器以及构建模型时，都拒绝可能或必须找到已存在聚合的命令上的 `@OccurredAt`。
- **不声明创建人。** 导入的提交记录在导入程序的调用方名下，就像任何提交记录在其发送方名下，所以快照的 `firstOperator` 是导入程序。源系统中是谁创建了订单，若领域需要，是导入事件的一个字段。
