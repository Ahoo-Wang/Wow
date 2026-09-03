---
title: 完成语义
description: 选择满足调用方可见性与副作用合同的最早命令阶段，并正确处理分支、函数匹配、链式等待与超时。
outline: deep
---

# 完成语义

命令“完成”不是单一时刻，而是调用方选择的观察点。先确定响应后必须成立的事实，再等待满足该合同的最早阶段；更晚的阶段会增加延迟，却不会自动提供调用方不需要的保证。

## 选择最早满足契约的阶段

| 阶段 | 成功时可以确认 | 不能据此确认 |
| --- | --- | --- |
| `SENT` | `CommandBus` 已接受命令 | 聚合已处理、事件已追加 |
| `PROCESSED` | 命令处理管线到达处理结果 | 快照、投影、事件处理器或 Saga 已完成 |
| `SNAPSHOT` | 该聚合状态的快照分支已完成 | 任一投影、事件处理器或 Saga 已完成 |
| `PROJECTED` | 匹配的投影函数已处理到最后一条事件 | 其他投影或其他下游分支已完成 |
| `EVENT_HANDLED` | 匹配的事件处理函数已完成 | 投影、Saga 或外部系统最终一致 |
| `SAGA_HANDLED` | 匹配的 Saga 函数已完成 | Saga 发出的后续命令也已完成 |

例如，写接口只需确认命令已进入总线时选择 `SENT`；响应后必须立即读取聚合处理结果时至少选择 `PROCESSED`；只有响应合同要求特定读模型可见时才等待对应 `PROJECTED`。

## 阶段依赖图

`SNAPSHOT`、`PROJECTED`、`EVENT_HANDLED` 和 `SAGA_HANDLED` 都依赖 `PROCESSED`，但彼此是独立分支，不是一条全局线性链：

![WaitingForStage](/images/wait/WaitingForStage.svg)

因此，观察到 `PROJECTED` 不表示 `SNAPSHOT` 或 `SAGA_HANDLED` 已完成。阶段信号按实际观察顺序到达；分布式通知、调度和并发处理都可能让下游信号先于 `PROCESSED` 被等待端观察到。

## SENT 与 PROCESSED

`SENT` 是发送边界：`CommandGateway.sendAndWaitForSent` 在 `CommandBus.send` 成功后生成结果。它适合低延迟接收确认，不承诺业务规则已经执行。

`PROCESSED` 来自命令处理管线。它是所有后续阶段的共同前置条件，也是判断命令处理成功或失败的观察点。失败结果的持久化含义不能只由阶段名推断，见[失败与幂等](./reliability.md)。

## SNAPSHOT 与下游分支

`SNAPSHOT` 表示快照分支完成。快照是可替换的加载检查点，不是权威事件历史；两者的边界见[事件溯源](../domain/event-sourcing.md)。

三个函数型下游阶段分别观察不同 Dispatcher：

- `PROJECTED`：投影函数；等待状态只有在匹配函数的 `isLastProjection == true` 信号到达后，才把该阶段视为完成。
- `EVENT_HANDLED`：[事件处理函数](../event/processor.md)。
- `SAGA_HANDLED`：[无状态 Saga 函数](../event/saga.md)；信号还可以携带该函数发出的后续 `commandId`。

等待其中一个分支不会隐式等待另外两个分支。若响应必须同时满足多个独立分支，应由应用明确组合这些合同，而不是选择一个“最晚”阶段来代替。

## 函数匹配

`PROJECTED`、`EVENT_HANDLED` 和 `SAGA_HANDLED` 可以按 `contextName`、`processorName`、`functionName` 定位函数：

```kotlin
val waitPlan = CommandWait.projected(
    waitCommandId = message.commandId,
    contextName = "order",
    processorName = "OrderProjection",
    functionName = "onOrderCreated",
)
```

空的匹配字段是通配条件；非空字段必须相等。使用尽可能具体的函数身份，避免同一阶段的其他函数信号提前满足等待。`SENT`、`PROCESSED` 和 `SNAPSHOT` 不按函数筛选。

## 链式等待

`CommandWait.chain` 先等待匹配的 `SAGA_HANDLED` 主函数，从其信号取得 Saga 发出的后续 `commandId`，再对每条后续命令等待指定的尾阶段和函数。它表达的是“这次 Saga 以及它实际发出的命令”，不是等待系统中所有同类命令。

后续命令的信号可能早于主 Saga 信号到达。链式等待会先暂存这些信号；主信号确认实际的后续 `commandId` 后，再创建尾部状态并按原观察顺序重放。未被主信号确认的暂存信号不能完成链。

![WaitingForChain](/images/wait/WaitingForChain.svg)

## 最终结果与结果流

[发送命令](./sending.md)中的两个 Gateway API 使用同一等待状态，但暴露方式不同：

- `sendAndWait` 返回完成等待计划的最终 `CommandResult`；前置阶段和目标阶段的 `result` 会由等待状态累计。
- `sendAndWaitStream` 按实际到达顺序发出每个 accepted signal 对应的 `CommandResult`；同一阶段可能出现多条元素。

对于 `SNAPSHOT` 和三个下游阶段，目标信号即使先到也不会立即完成：等待状态会保留它，直到 `PROCESSED` 也被观察到。若前置阶段失败，等待会以该失败信号提前结束，而不会继续等待目标分支。

## 超时、取消与未知结果

等待计划默认使用 30 秒的调用方端到端期限，可用 `withTimeout` 设置正数时长。期限覆盖预检、发送和等待阶段；超时或订阅取消会释放本地等待句柄，但不会撤销已被总线接受或已经执行的命令。

因此超时表示“本次调用没有在期限内观察到合同结果”，不表示命令失败，也不表示事件未追加。把它作为未知结果处理：保留原 `requestId`，先查询权威状态，再按[失败与幂等](./reliability.md)的流程决定是否重试。
