---
title: 核心概念
description: 用稳定术语理解 Wow 的命令、聚合、事件、状态、等待、投影、Saga 与恢复。
outline: deep
---

# 核心概念

本页只统一术语并指向权威页面。实现步骤、运行时状态机和完整示例由对应能力页负责。

```text
命令载荷
  → CommandMessage 信封
  → 聚合决策
  → DomainEventStream 中的领域事件载荷
  → 溯源聚合状态
  → 投影 / Saga / 其他处理器
```

## 术语摘要

| 术语 | 在 Wow 中的稳定含义 | 主要工件 |
| --- | --- | --- |
| 限界上下文 | 拥有聚合定义与业务语言的命名边界 | `@BoundedContext` |
| 聚合 | 由上下文、聚合名、租户和 ID 标识的一致性边界 | `NamedAggregate`、`AggregateId` |
| 命令 | 请求改变状态的祈使式载荷 | 数据类/对象、`@CreateAggregate`、`@CommandRoute` |
| 命令消息 | 携带命令、身份、请求、版本、Header 与路由元数据的运行时信封 | `CommandMessage<C>` |
| 命令聚合根 | 校验不变量并返回事件的领域对象 | `@AggregateRoot`、`@OnCommand` |
| 状态聚合根 | 只通过溯源事件重建的状态对象 | `@OnSourcing` |
| 领域事件 | 不可变的业务事实载荷 | 数据类/对象；显式元数据使用 `@Event` |
| 领域事件信封 | 增加聚合、命令、序列、修订和时间元数据的运行时事件 | `DomainEvent<T>` |
| 事件流 | 一次聚合命令产生的有序事件批次 | `DomainEventStream` |
| 事件存储 | 追加和加载聚合事件流的权威历史合同 | `EventStore` |
| 快照 | 加速聚合恢复的可替换派生检查点 | `SnapshotStore` |
| 等待阶段 | 调用方选择的命令完成定义 | `SENT`、`PROCESSED`、`SNAPSHOT`、`PROJECTED` 等 |
| 投影 | 消费事件并维护读模型的处理器 | `@ProjectionProcessor`、`@OnEvent` |
| Saga | 消费事件并发送后续命令的跨聚合协调器 | `@StatelessSaga`、`@OnEvent` |
| 事件补偿 | 对失败事件处理工作的可观察记录、调度与重试 | 补偿记录、`RetrySpec` |

## 关键边界

- 命令处理函数根据当前状态做决定并返回事件；状态只由确定、无副作用的溯源函数改变。
- EventStore 中已追加的事件流是权威历史；快照和投影是可重建的派生数据。
- `SENT`、`PROCESSED`、`SNAPSHOT` 与函数级下游阶段证明不同边界，不能相互替代。
- Processor 承载普通副作用，Saga 发送跨聚合后续命令，事件补偿重试失败函数；业务反向动作仍由领域命令表达。
- 投影完成不自动证明查询可见；需要用户可见结果时，应执行真实查询验证。

## 权威阅读入口

| 能力 | 权威页面 |
| --- | --- |
| 领域边界、历史与恢复 | [领域模型](./domain/)、[聚合与不变量](./domain/aggregate.md)、[事件溯源](./domain/event-sourcing.md)、[事件演进](./domain/event-evolution.md)、[快照](./domain/snapshot.md)、[聚合生命周期](./domain/lifecycle.md) |
| 命令定义、调用与结果 | [命令](./command/)、[定义命令](./command/definition.md)、[发送命令](./command/sending.md)、[API Client](./command/api-client.md)、[完成语义](./command/completion.md)、[失败与幂等](./command/reliability.md) |
| 命令运行时原理 | [命令处理管线](./command/internals/pipeline.md)、[命令等待运行时](./command/internals/wait-runtime.md)、[命令传输与路由](./command/internals/transport.md) |
| 事件处理与跨聚合协作 | [事件与协作](./event/)、[事件处理器](./event/processor.md)、[Saga](./event/saga.md)、[事件补偿](./event/compensation.md)、[事件分发管线](./event/dispatch.md) |
| 读模型与查询 | [投影](./projection.md)、[查询服务](./query.md)、[数据权限](./data-access.md) |
