---
title: 传统架构迁移
description: 按 bounded context 采用 Wow，并以单写者、可重放导入、对账与回滚推进。
---

# 传统架构迁移

本页适用于尚未拥有 Wow 事件历史的系统。安全路径是渐进式：确定一个 bounded context，构建模型，在旧
系统保持权威时完成导入与 shadow，先切读，再切写。任何时刻都必须只有一个系统拥有业务写入。

已经使用 Wow v6 的系统应改用 [Wow v6 迁移到 v8](./v6-to-v8.md)。

## 迁移总览

| 阶段 | 权威 writer | 交付物 | 完成证据 |
|---|---|---|---|
| 0. 边界 | Legacy | aggregate/ID/tenant/invariant mapping 与验收场景 | 领域负责人批准范围和语言 |
| 1. Wow 模型 | Legacy | command、aggregate 行为、domain event、state sourcing、测试 | 成功/拒绝/幂等场景通过 |
| 2. 导入与追平 | Legacy | 可重放导入命令、outbox/CDC feed、source watermark | lag 与逐 aggregate 对账满足阈值 |
| 3. 切读 | Legacy | 面向受控 cohort 的 Wow query/read model | 业务结果与延迟对账，回滚已测试 |
| 4. 切写 | Wow | admission 开关、已排空 legacy writer、Wow command path | 单写者证据、新写入对账、回滚可执行 |
| 5. 收尾 | Wow | 观察窗后移除 legacy write/synchronizer | 无未解决 drift 或回滚依赖 |

不要先把表复制到 event store。事件记录的是已接受的领域决策，历史转换必须经过显式、可评审的契约。

## 1. 先迁移边界，不先迁移表

选择低耦合业务能力，并写清：

- 稳定 aggregate identity、tenant/owner/space mapping 与 aggregate boundary；
- 边界接受哪些 command，谁有权发送；
- 哪些 invariant 会拒绝 command；
- 接受后产生哪些 domain event；
- state sourcing 与删除语义；
- 哪些外部调用属于 projection/Saga，而不是 aggregate state transition。

遵循仓库示例结构：API command/event 与 aggregate 实现分离，`AggregateSpec` 在无基础设施情况下验证行为。

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        whenCommand(CreateOrder(id = "order-1", ...)) {
            expectNoError()
            expectEventType(OrderCreated::class)
            expectState { id.assert().isEqualTo("order-1") }
        }
    }
})
```

还要覆盖重复 request ID、非法 transition、已删除 aggregate、tenant/owner 传播，以及实际提交 event shape
的序列化。Aggregate 能编译只属于源码证据，不能证明历史行能映射成合法领域决策。

通过 anti-corruption adapter 把 legacy contract 转为 Wow command。除非确实是业务概念，不要把旧 column
name 与 sentinel value 带进新的公开领域 API。

## 2. 用单写者完成历史导入与增量追平

使用耐久、可恢复的迁移 manifest，至少记录 source partition/key、source version/update watermark、target
aggregate ID、确定性 request ID、状态、source checksum、target version/checksum、错误与最后验证 batch。

推荐流程：

1. 在 legacy 写入继续时取得 source snapshot，并记录 high watermark；
2. 通过显式 `Import...`/`Synchronize...` command contract 转换每个 source entity；
3. 使用确定性 request ID，确保已完成 batch 可幂等重放；
4. 只有获得 target acknowledgement 并验证后，才持久化 manifest 进度；
5. 从 snapshot watermark 之后按 source 顺序消费 outbox/CDC 变化；
6. 重复对账，直到 lag 持续满足批准阈值。

source row 只是定位器；经过评审的 mapping 决定它代表什么 Wow command/event/state。保留 source payload/
checksum 作为审计证据，不要把转换选择隐藏在临时脚本里。

禁止一次请求分别独立提交 legacy database 与 Wow 的“双写”。使用 outbox/CDC 或其他耐久 handoff。
synchronizer 失败时，legacy 仍是唯一 writer，target 从最后 manifest/watermark 继续追平。

对账至少要逐 aggregate 和全局比较：

- source population 与 imported ID，包括 missing/extra ID；
- 最新 source revision/update time 与 target aggregate version；
- 金额、数量、状态、删除等业务 invariant；
- 重复确定性 request ID 与 failed/dead-letter 记录；
- event/snapshot 数量、代表性完整 replay 与 read-model 行。

## 3. 对账后分别切换读与写

条件允许时先切读：

1. 用同一请求 shadow-query legacy 与 Wow read model；
2. 把差异分类为 mapping defect、预期语义变化、lag 或数据损坏；
3. writer 仍由 legacy 拥有时，切换受控 read cohort；
4. 观察 error rate、latency、lag、业务结果、metric 与 trace；
5. 门禁失败时只回滚读，不改变 writer。

写切换是单独维护动作：

1. 关闭 legacy write admission，排空 in-flight transaction/outbox record；
2. 记录最终 source watermark，批准范围内的 reconciliation drift 必须为零；
3. 只为一个受控 cohort/instance 开启 Wow command boundary；
4. 验证 command result、committed event、reconstructed state、projection/Saga 与外部 side effect；
5. 观察门禁通过后才扩大流量。

第一次 Wow 生产写入前，可把读取切回 legacy 并丢弃/重建 shadow target。第一次写入后，回滚必须停止
Wow，把这些写入传回或反向转换到 legacy authority，并重新对账两侧。只恢复旧应用会丢失已接受决策。

本地测试、导入演练成功与 canary 健康是三类不同证据。生产准入还要求已审批 image/revision、真实 routing、
monitoring/alert 与明确 incident owner。

## 4. 领域模型继续演进

切换后通过新 command/event 与显式序列化契约演进；不要仅为了让当前 class shape 更方便而改写 committed
event history。

每次变更都应：

- 证明旧 event 仍能反序列化并 source 成预期当前 state，或提供已评审离线历史转换；
- 使用新 event 显式表达新语义，不要静默改变已有 field 含义；
- derived shape 变化时从 event 重建 snapshot 与 projection；
- 删除旧 field/migration adapter 前对账 state/read model；
- 固定 rollback version，并决定它如何处理新版本写出的 event。

snapshot 或 projection 重建成功不能修复错误 event mapping；代表性和边缘 replay 必须验证业务 state。

## 完成检查清单

- [ ] bounded context、aggregate identity、tenant/owner mapping、invariant 与排除项已批准
- [ ] command/event/state 契约及 AggregateSpec 拒绝/幂等场景通过
- [ ] import/CDC manifest 耐久、可恢复、确定性
- [ ] 每个阶段都能证明只有一个权威 writer
- [ ] source/target 数量、版本、checksum、invariant、replay 与 read model 已对账
- [ ] 读切换与写切换分别完成演练
- [ ] 第一次 Wow 写入前后的回滚都已演练
- [ ] deployed revision、真实流量、metric/trace、alert 与业务检查通过
- [ ] 观察窗关闭前保留 legacy write 与临时同步链路

## 相关页面

| 页面 | 关系 |
|---|---|
| [迁移指南](../migration.md) | 范围与共同证据门禁 |
| [聚合与不变量](../domain/aggregate.md) | Aggregate 与 command/event 设计 |
| [测试](../test-suite.md) | 领域行为验证 |
| [商业智能](../bi.md) | 可重建的分析读模型 |
| [Wow v6 迁移到 v8](./v6-to-v8.md) | 既有 Wow 版本升级 |

<!-- Sources: example order API/domain/spec, CommandFactory/CommandGateway idempotency path,
event/snapshot/query contracts, and Wow test DSL -->
