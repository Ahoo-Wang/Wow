---
title: 备份、恢复与重放
description: 以 EventStore 为聚合权威，恢复 Wow 快照、投影、消息位点、补偿状态并建立可回滚证据。
outline: deep
---

# 备份、恢复与重放

数据库工具负责生成备份文件；Wow 恢复的完成条件是重新建立并证明这条链路：

```text
EventStore → Aggregate/StateEvent → Snapshot/Projection/Processor/Saga
                     ↕
                Broker offsets
```

恢复命令可能再次执行 Handler。任何重放前都要隔离流量与外部副作用，并先证明幂等。

## 先划分权威数据与派生数据

| 数据 | Wow 角色 | 恢复所有者与要求 |
| --- | --- | --- |
| DomainEventStream | 聚合状态的权威历史 | EventStore owner 恢复版本顺序、revision、request-id 与唯一约束 |
| Snapshot | 聚合加载检查点；`all` 时也可作为当前状态查询存储 | Snapshot owner 可恢复备份或从 EventStore 重建 |
| Projection/外部读模型 | 面向查询的派生状态 | 每个 Projection owner 提供清空、断点、重放、幂等和对账流程 |
| Broker message/offset | 尚未完成的异步工作 | Bus owner 与 EventStore cutoff 协调，避免遗漏或未经验证的重复 |
| Compensation 记录 | 自动/人工失败恢复状态 | Compensation owner 分别保留两个独立维度：`ExecutionFailedStatus`（`FAILED` / `PREPARED` / `SUCCEEDED`）与 `RecoverableType`（`RECOVERABLE` / `UNKNOWN` / `UNRECOVERABLE`） |
| PrepareKey、context/schema/index、storage route 配置 | 唯一性、上下文归属与实际 binding | 对应后端与应用配置 owner 一起恢复并校验 |
| 外部副作用 | 支付、通知、第三方写入等 | 不能从 EventStore 自动回滚；应用 owner 做业务对账 |

“事件可重放”只说明聚合状态可重建。它不自动恢复 Broker 中已丢消息，也不能撤销已经发生的外部副作用。

## 恢复计划必须先回答的问题

1. 每个 EventStore binding、SnapshotStore binding、Projection store 与 Broker 的 RPO/RTO 是什么？
2. 一致 cutoff 用数据库时间点、事件版本、consumer offset 还是停机窗口表示？
3. 谁能关闭命令入口、Dispatcher、定时任务和真实外部系统？
4. 哪些 Handler 可以安全重复，幂等键是什么？
5. offset 早于 EventStore cutoff 时怎样接受重复；晚于 cutoff 时怎样补回遗漏？
6. 候选及回滚应用能否读取备份中的所有 event name/revision 与后端布局？
7. 修改性内置 route 由谁鉴权、审计、限流并批准？

缺少任一答案时，只能声明“已生成备份”，不能声明“已验证恢复”。

## 备份流程

### 1. 固化清单

从生效配置和 `storage-routing` 生成实际清单，而不是假设所有聚合使用默认后端：

- `context.aggregate` 到 EventStore/SnapshotStore binding 的映射；
- Kafka topic/partition/group/offset，或 Redis Stream/group/pending 状态；
- EventStore、Snapshot、Projection、PrepareKey、补偿与 schema/index 存储；
- 应用构建标识、Wow 依赖锁定、脱敏配置摘要和 event revision 分布；
- 每个修改性 Handler 的幂等键、外部系统和负责人。

### 2. 选择一致的截止点

最容易证明的流程是：先摘除命令入口，停止产生新工作的 scheduler，等待已准入的 WowRuntime 工作静默，再停止消费者并记录位点，最后对所有后端取备份。在线快照必须记录每个系统的实际 cutoff；多个数据库与 Broker 不会因为同一时间执行命令就自动成为原子快照。

### 3. 同时保存证据

备份旁保存可机器比较的基线：

- 每个 context/aggregate/tenant 的 stream 数、事件数和 head version；
- event name/revision 分布与不可反序列化计数；
- Snapshot 数量、最大版本和 `snapshot.version <= event head` 违反数；
- Projection 的高风险业务总量与 tenant/owner/space 隔离结果；
- consumer offset、lag、pending、重试数量，以及分别按 `ExecutionFailedStatus` 和 `RecoverableType` 汇总的补偿数量；
- 备份校验和、工具参数、耗时和实际 cutoff。

没有恢复前基线，就无法区分恢复后的缺失、重复和历史上已经存在的异常。

## 隔离恢复顺序

1. **建立空白隔离环境**：使用独立 database/index/topic/Stream/consumer group/凭据；阻断真实支付、通知和第三方写入。
2. **恢复 EventStore 及其约束**：恢复事件、context/schema、唯一索引和 route 指向的每个实际 binding。
3. **验证事件历史**：逐 stream 检查初始版本、连续版本、head、request-id、event name/revision 与反序列化。
4. **启动候选但保持入口关闭**：核对生效配置只指向恢复副本；确认 capability、template/index 与 Bean 装配。
5. **恢复或重建 Snapshot**：先抽样单聚合，再分页批量执行；结果版本不得超过 EventStore head。
6. **重建 Projection/查询模型**：只运行目标函数，记录 after-id/offset、失败项和可重入点；Wow 没有替应用提供一个通用 Projection 清空命令。
7. **协调 Broker 位点**：回退前证明 Handler 幂等；保留较新位点前证明没有恢复点后的事件被跳过。
8. **恢复补偿的两个维度**：`ExecutionFailedStatus` 只能保留为 `FAILED`、`PREPARED` 或 `SUCCEEDED`；另行保留 `RecoverableType` 的 `RECOVERABLE`、`UNKNOWN` 或 `UNRECOVERABLE`。不能从其中一个推断另一个，也不能因“重新投递”而清空失败记录。
9. **完成对账后逐级开放**：先只读查询，再受控测试命令，最后恢复业务入口和 scheduler。

当 `webflux-support` 装配对应 aggregate route 时，运行时 OpenAPI 会列出这些恢复操作：

| 操作 | 方法与 route 后缀 | 实际行为 |
| --- | --- | --- |
| Regenerate Aggregate Snapshot | `PUT .../{aggregateId}/snapshot` | 从 EventStore 重放该聚合并保存 Snapshot |
| Batch Regenerate Aggregate Snapshot | `PUT .../snapshot/{afterId}/{limit}` | 按 aggregate-id 游标分页重建 |
| Resend State Event | `POST .../state/{afterId}/{limit}` | 从 EventStore 重建状态并发送带 compensation target 的 StateEvent |
| Event Compensate | `PUT .../{aggregateId}/{version}/compensate` | 向请求体指定的目标补偿单个 DomainEventStream |

完整前缀、tenant 参数和 operationId 由 aggregate route 合同决定，必须从**候选运行时的 OpenAPI**读取，不能从示例猜测。上述修改性 route 没有独立的通用管理开关；应用必须放在受控管理面并配置鉴权、审计、批量上限和审批。StateEvent resend 不等于重放所有 DomainEvent Handler，Event compensate 也不是全量 Projection rebuild。

## 对账矩阵

| 边界 | 至少验证 |
| --- | --- |
| EventStore | stream 数、版本连续、head、request-id 唯一、event name/revision |
| Aggregate state | 从 `1..head` 完整 sourcing 的状态与业务基线一致 |
| Snapshot | `snapshot.version <= event head`，抽样内容等于完整重放 |
| Projection | 行数、高风险金额/库存/权限、tenant 隔离、删除状态、索引计划 |
| Processor/Saga | 重投不会重复命令、扣款、通知或遗漏 |
| Broker | topic/Stream、partition/group、offset、lag、pending 与失败队列 |
| Compensation | `ExecutionFailedStatus` 分布、独立的 `RecoverableType` 分布、重试次数、目标函数、人工决定与外部效果一致 |
| Runtime | stage 延迟、错误率、trace、告警和优雅停止仍满足候选基线 |

资金、库存、权限等高风险域需要全量业务对账；抽样只适合在全量结构校验之外增加内容核查。

## 验收请求

恢复环境至少执行并保存以下证据：

1. 加载一个没有可用 Snapshot、必须完整重放的聚合。
2. 重建同一聚合 Snapshot，并与完整重放状态及版本比较。
3. 查询一个重建后的 Projection，并追溯到源 event revision。
4. 使用历史 `requestId` 重试同一逻辑命令，确认没有第二次业务执行。
5. 发送新测试命令，分别验证所需的 `PROCESSED`、`SNAPSHOT` 和精确函数 stage。
6. 重启候选实例，确认 EventStore head、Snapshot、consumer offset 与补偿状态不回退。
7. 执行一次可回滚的失败注入，证明失败项能从 after-id/offset 继续，而不是从头盲目重放。

## 回滚门禁

- 原始备份和第一次恢复结果保持只读；重建写入使用可丢弃的隔离 namespace。
- 回滚二进制必须先读取全部现有 event revision、配置键和存储布局；“能启动”不等于能处理新事件。
- 开放流量后产生的新事件、Broker offset 和外部副作用不在旧备份中。回滚前先再次停流、记录增量并选择前滚修复或数据/代码协同回滚。
- 重建 Snapshot/Projection 可丢弃并重来；EventStore、补偿记录或外部副作用不可用同一方式回滚。
- 每个批量操作保留 after-id/limit、目标函数、调用者、时间、结果与失败明细。

## 演练频率与完成标志

按业务 RPO/RTO 和变化风险安排演练，并在空白环境从真实备份开始。只有测得恢复耗时满足 RTO、实际数据损失满足 RPO、所有结构校验与高风险业务对账通过、回滚边界已验证，才能标记完成。未覆盖的后端、Handler 或外部系统必须明确记录为 `MISSING EVIDENCE`，不能被绿色单元测试替代。

## 相关页面

- [事件溯源](./domain/event-sourcing.md)
- [快照](./domain/snapshot.md)
- [事件演进](./domain/event-evolution.md)
- [事件补偿](./event/compensation.md)
- [MongoDB](./extensions/mongo.md)、[Redis](./extensions/redis.md)、[Elasticsearch](./extensions/elasticsearch.md)
- [BI 部署与恢复](./bi-operations.md)
- [生产最佳实践](./best-practices.md)
