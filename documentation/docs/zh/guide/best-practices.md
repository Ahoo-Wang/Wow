---
title: 生产最佳实践
description: 以 Wow 命令处理阶段为主线，落实建模、非阻塞、幂等、快照、补偿、测试与生产证据。
outline: deep
---

# 生产最佳实践

Wow 提供命令、事件溯源、消息处理和等待阶段，但应用仍拥有业务不变量、外部副作用、后端拓扑与恢复结果。实践应落到具体运行时 stage 和可重复证据；与 Wow 链路无关的通用运维清单不在本页重复。

## 实践地图

| Wow 边界 | 推荐做法 | 必须保留的证据 |
| --- | --- | --- |
| Command → Aggregate | 命令表达意图；聚合保护不变量 | 成功、拒绝、并发分支的 `AggregateSpec` |
| Aggregate → EventStore | 只通过领域事件变更状态 | 事件版本连续、冲突与 request-id 测试 |
| EventStore → DomainEventBus | Handler 可重试，副作用幂等 | 重投/故障注入与 Broker ACK 证据 |
| StateEvent → Snapshot | 当前状态查询使用 `strategy: all` | `SNAPSHOT` 写后读与完整重放对账 |
| Projection/Processor/Saga | 等待精确函数目标 | 函数标识、lag、补偿与副作用对账 |
| WowRuntime | 入口摘流后静默并逆序停止 | 终止窗口内的停机和剩余积压结果 |

## 建模业务决策，而不是数据更新

命令使用业务动作命名，命令聚合判断该动作是否允许，领域事件记录已经接受的事实，状态聚合只在 sourcing 函数中确定性应用事件。

| 元素 | 应用职责 | 不应做 |
| --- | --- | --- |
| Command | 包含执行决策所需输入与稳定 request-id | 暴露任意字段更新 |
| Command Aggregate | 校验不变量并返回领域事件 | 直接修改对外可见状态存储 |
| Domain Event | 记录过去式业务事实并可长期演进 | 把临时 DTO 当作事件合同 |
| State Aggregate | 从事件顺序确定性重建状态 | sourcing 时访问网络、时间或随机数 |

聚合边界只覆盖必须原子决策的不变量。跨聚合流程用事件与 Saga 连接；不要为减少一个异步阶段而扩大共享聚合。

## 保持响应式边界

`CommandGateway`、Bus、Dispatcher、EventStore、Projection 与 Saga 都以 `Mono`/`Flux` 组合。核心路径中的 `block()`、同步数据库驱动或隐藏线程等待会占用处理资源，并让 `wow.shutdown-timeout` 无法证明已准入工作可以排空。

只有外部 SDK 确实无法非阻塞时，才在应用 Adapter 边界使用有容量上限的 Scheduler 隔离，并记录队列、超时和拒绝指标。`@Blocking` 是隔离标记，不会修复慢查询、无界并发或不可取消的 I/O。

## 等待真正需要的业务结果

| Stage | 已证明 | 未证明 |
| --- | --- | --- |
| `SENT` | CommandBus 接受发送 | 聚合已执行 |
| `PROCESSED` | 命令 Filter 链完成，包括聚合决策、EventStore append 与 DomainEventBus send | 下游消费者完成 |
| `SNAPSHOT` | Snapshot Dispatcher 完成本次 StateEvent 处理 | 所有投影完成；`version_offset` 一定写了新快照 |
| `PROJECTED` | 目标投影完成；无函数目标时等待 last projection 信号 | 其他事件处理器或 Saga 完成 |
| `EVENT_HANDLED` | 目标事件处理函数完成 | Saga 派生命令完成 |
| `SAGA_HANDLED` | 目标 Saga 处理源事件，派生命令已被发送/接受 | 下游聚合已完成或分布式事务提交 |

函数型阶段应指定 `contextName`、`processorName`、`functionName`，避免等待一个无关处理器。`WaitPlan.withTimeout` 只限制调用方本地等待，不写入命令 Header；超时表示结果未知，不表示命令未执行。选择能证明 API 合同的最窄阶段，避免把 API 可用性耦合到不相关消费者。

## 明确重试、并发与 LocalFirst 语义

| 机制 | 用途 | 边界 |
| --- | --- | --- |
| `requestId` | 标识一次逻辑命令重试 | 重试同一业务动作必须复用；新 ID 会成为新命令 |
| `aggregateVersion` | 拒绝基于陈旧状态的写入 | 只有业务允许任意当前版本时才省略 |
| Aggregate retry | 重试分类为 recoverable 的聚合失败 | 有限退避；持续冲突必须处理热点或边界 |
| LocalFirst | 本地准入后减少 Broker 往返 | 不是 exactly-once；准入后 Handler 失败不会重开分布式副本 |

幂等必须一直延伸到外部副作用：使用业务唯一键、事件 ID 或目标版本，在重试前读取当前状态。Broker 重投、调用方超时重试和运维补偿是三个独立来源，都要覆盖。

## 将快照作为默认查询存储

对于单聚合当前状态查询，优先使用 `strategy: all` 与支持查询的 SnapshotStore，避免把同一状态复制到额外投影。MongoDB 和 Elasticsearch 提供 SnapshotQueryService；Redis 与 in-memory SnapshotStore 可以保存/加载快照，但没有通用动态查询实现。

| 需求 | 选择 | 验收 |
| --- | --- | --- |
| 当前状态与写后读 | `all` + 同一查询后端 | 等待 `SNAPSHOT` 后查询目标版本 |
| 只优化聚合加载、允许查询陈旧 | `version_offset` | 记录允许的版本差并从 EventStore 抽样重放 |
| 跨聚合/反范式/外部读模型 | Projection | 精确 `PROJECTED` 目标、幂等、重建与对账 |

快照是派生检查点，EventStore 才是聚合历史权威。查询索引、tenant/owner/space 过滤和权限仍由应用拥有。

## 有意识地编排与补偿

Saga 表达跨聚合编排，不是 ACID 分布式事务。`SAGA_HANDLED` 只覆盖源事件函数和可能的命令发送边界。若调用方需要下游聚合完成，应使用等待链或读取可验证状态，而不是扩大该 stage 的含义。

自动重试耗尽或错误不可恢复时，补偿记录应保存目标函数、错误、重试状态与人工决定。运维重发之前必须先证明 Handler 幂等，并明确重发的是领域事件、重建后的状态事件还是单个失败函数；三者影响范围不同。

## 在足够窄的层次测试行为

| 测试层 | 最小断言 | 工具 |
| --- | --- | --- |
| 聚合 | 命令接受/拒绝、事件、状态 | `AggregateSpec` |
| Saga | 源事件产生的命令与分支 | `SagaSpec` |
| Adapter | EventStore/SnapshotStore/Bus/查询合同 | `wow-tck` |
| 应用集成 | 生成元数据、序列化、真实 Bus/Store 与 Spring 装配 | production-like integration test |
| 运维 | 重投、重建、备份恢复、对账、停机 | 隔离演练 |

先在最窄层复现，再只为基础设施行为增加集成测试。字符串断言或 mock 不能证明真实 Mongo、Redis、Kafka、Elasticsearch 行为。

## 生产就绪检查清单

| 门禁 | 通过条件 | 证据 |
| --- | --- | --- |
| `SENT` | 目标 Bus 的发送、认证、topic/Stream 与错误路径验证 | Broker 测试、ACL、lag/ACK 记录 |
| `PROCESSED` | 聚合规格、EventStore 并发/幂等与事件演进通过 | 测试报告、版本/revision 样本 |
| `SNAPSHOT` | 策略、StateEvent 消费、查询后端和重建通过 | 写后读、完整重放对账、索引计划 |
| 函数 stages | 目标函数匹配、重投幂等、补偿可操作 | function identity、故障注入、补偿记录 |
| HTTP | 实际 route 受认证授权与 query guard 保护 | 运行时 OpenAPI、授权/限流测试 |
| Lifecycle | 摘流、静默、逆序停止和失败关闭满足预算 | 滚动停机时间线与剩余积压 |
| Recovery | EventStore、派生状态、位点、补偿通过隔离恢复 | 校验和、RPO/RTO、业务对账 |

只有目标拓扑和生产同构数据量的证据才能支持生产声明。框架模块 checks 证明源码回归，不能单独证明容量、部署或恢复。

## 相关页面

- [核心概念](./core-concepts.md)
- [聚合建模](./modeling.md)
- [命令网关](./command-gateway.md)
- [快照](./snapshot.md)
- [查询](./query.md)
- [分布式事务（Saga）](./saga.md)
- [事件补偿](./event-compensation.md)
- [备份、恢复与重放](./recovery.md)
- [测试套件](./test-suite.md)
- [可观测性](./advanced/observability.md)
