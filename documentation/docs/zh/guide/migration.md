---
title: 迁移指南
description: 选择 Wow 迁移路径，并严格区分源码、运行时、存储、数据与生产证据。
---

# 迁移指南

“迁移”不是单一兼容结论。任何变更前都要分别记录五个范围：

| 范围 | 要回答的问题 | 典型证据 |
|---|---|---|
| 源码 | 应用能否针对固定目标 API 编译？ | compiler、单测、生成元数据 diff |
| 运行时 | 目标生命周期/配置能否启动、ready、处理工作并正确停机？ | 集成测试、readiness、优雅停机 trace/log |
| 存储 | 目标能否读写精确的 event、snapshot、Redis/Mongo 与 BI 布局？ | tag-to-tag 契约 diff、离线 inventory、格式测试 |
| 数据 | 数量、版本、request ID、索引、回放状态与读模型是否对账？ | manifest、checksum、代表性/全量对账 |
| 切换 | 已审批生产 revision 是否真实运行、可观测且有演练过的回滚？ | deployment digest/revision、真实流量、告警与回滚证据 |

本地 build 绿色只能关闭源码门禁，不能关闭其他四项。

## 选择迁移路径

| 当前系统 | 主路径 | 原因 |
|---|---|---|
| CRUD/事务脚本/直接写表，没有 Wow 历史 | [传统架构迁移](./migration/traditional-architecture.md) | 建立 command、aggregate、event、导入与流量所有权 |
| 精确 Wow v6 tag | [Wow v6 迁移到 v8](./migration/v6-to-v8.md) | 比较固定平台/API/存储契约，并在需要时执行数据硬切换 |
| Wow v8 上有自定义 dispatcher/message-bus/Spring 生命周期 owner | [运行时编排迁移](./migration/runtime-orchestration.md) | 把生命周期源码迁移到统一 `WowRuntime`；它不自动等于数据迁移 |
| Wow v8.16.x 使用旧查询 API 或 `SnapshotRepository` | [V9 查询迁移](./query/v9-query-migration.md) | 迁移 Gateway/Backend、Filter、Mask、SnapshotStore 与 Spring Bean 名 |

不要把首次采用 Wow 与 v6→v8 升级混成一次无法区分的发布。每个变更窗口都应选择一个 bounded context
及精确 source/target version。

## 文档边界

| 页面 | 负责 | 不负责 |
|---|---|---|
| 传统架构迁移 | 领域边界、历史导入、shadow 追平、读写切换 | Wow 版本/平台升级假设 |
| v6→v8 | 固定 Gradle/平台矩阵、源码破坏、存储格式、数据切换 | 重设计全部领域 |
| 运行时编排 | `RuntimeComponent`、message receiver admission、Spring 生命周期所有权、停机 | 除非其他章节明确要求，否则不转换 event/snapshot 格式 |
| V9 查询迁移 | 查询 Gateway/Backend、Filter/Mask、SnapshotStore 命名和 Condition 迁移窗口 | 部署或生产切换证明 |
| 运行时生命周期 | 迁移后的稳定语义 | 迁移步骤本身 |

[Release Notes](https://github.com/Ahoo-Wang/Wow/releases) 描述版本变更；选定 tag 的源码、测试与 build 文件
才是精确契约。`main` 只能作为当前目标的证据。

## 共同完成门禁

只有当前门禁具备可复现证据后才能推进：

1. **范围**：固定 bounded context、source tag、target tag、dataset/store、负责人和明确排除项。
2. **基线**：源码测试绿色；盘点 event/snapshot/key/collection/read model；创建并验证可恢复 backup。
3. **演练**：在生产形态的隔离副本上运行同一迁移工具与 manifest。
4. **验证**：编译、启动、处理、回放、对账并优雅停止目标版本；覆盖失败路径。
5. **切换**：关闭 admission、排空旧 writer、只迁移一次、先启动一个目标实例，再转移受控流量。
6. **观察**：验证 metric/trace、backend version、projection/BI lag、告警与业务不变量。
7. **关闭**：回滚窗口结束后才移除旧 writer、旧数据与临时 bridge。

回滚计划必须区分“目标版本第一次生产写入之前”和“之后”。新存储格式已经写入时，只恢复旧 binary
不是回滚。

## 旧链接导航

旧单页主题已拆分到三份专项指南。以下标题与显式 alias 保留既有 deep link。

### 版本升级指南

<span id="升级步骤"></span>
<span id="依赖版本更新"></span>
<span id="破坏性变更检查"></span>

参见 [v6 → v8：通用升级步骤](./migration/v6-to-v8.md#通用升级步骤)。

### 从传统架构迁移

<span id="迁移策略"></span>
<span id="渐进式迁移"></span>
<span id="迁移步骤"></span>

参见 [传统架构迁移：迁移总览](./migration/traditional-architecture.md#迁移总览)。

### 数据迁移

<span id="历史数据导入"></span>

参见 [用单写者完成历史导入与增量追平](./migration/traditional-architecture.md#_2-用单写者完成历史导入与增量追平)。

### 代码迁移

<span id="从-crud-到命令模式"></span>
<span id="从直接查询到查询快照"></span>

参见 [先迁移边界，不先迁移表](./migration/traditional-architecture.md#_1-先迁移边界-不先迁移表)
和 [对账后分别切换读与写](./migration/traditional-architecture.md#_3-对账后分别切换读与写)。

### 兼容性说明

<span id="数据格式兼容性"></span>
<span id="事件升级"></span>
<span id="消息格式兼容性"></span>

参见 [领域模型继续演进](./migration/traditional-architecture.md#_4-领域模型继续演进)
和 [v6 → v8：破坏性变更检查](./migration/v6-to-v8.md#破坏性变更检查)。

### 已知问题

<span id="版本特定问题"></span>
<span id="常见迁移问题"></span>

参见 [Release Notes](https://github.com/Ahoo-Wang/Wow/releases) 和
[故障排查](./troubleshooting.md)。应用 workaround 前，应先在精确固定 tag 上复现失败。

### 迁移检查清单

使用 [传统架构迁移检查清单](./migration/traditional-architecture.md#完成检查清单)
或 [v6 → v8 验证清单](./migration/v6-to-v8.md#验证清单)，再补充环境特定的生产准入证据。

### 回滚计划

使用所选专项指南的回滚步骤，并遵守[共同完成门禁](#共同完成门禁)中首次写入前后的区分。

### 统一运行时编排

参见 [运行时编排迁移](./migration/runtime-orchestration.md)。

### 移除版本化快照检查点

参见 [v6 → v8：移除版本化快照检查点](./migration/v6-to-v8.md#移除版本化快照检查点)。

### SnapshotStore 原子保存

参见 [v6 → v8：SnapshotStore 原子保存](./migration/v6-to-v8.md#snapshotstore-原子保存)。

### Redis EventStore Canonical v2 布局（v8.9.0 引入）

参见 [v6 → v8：Redis EventStore Canonical v2 布局](./migration/v6-to-v8.md#redis-eventstore-canonical-v2-布局-v8-9-0-引入)。

### Mongo 所有权保护

参见 [v6 → v8：Mongo 所有权保护](./migration/v6-to-v8.md#mongo-所有权保护)。

## 相关页面

| 页面 | 关系 |
|---|---|
| [传统架构迁移](./migration/traditional-architecture.md) | 首次采用与流量所有权 |
| [Wow v6 迁移到 v8](./migration/v6-to-v8.md) | 既有 Wow 平台/存储升级 |
| [运行时编排迁移](./migration/runtime-orchestration.md) | 统一生命周期源码迁移 |
| [V9 查询迁移](./query/v9-query-migration.md) | V8.16.x 到 V9 的查询与 SnapshotStore 源码迁移 |
| [运行时生命周期](./advanced/runtime-lifecycle.md) | 迁移后的稳定运行模型 |
| [故障排查](./troubleshooting.md) | 门禁失败时的证据化诊断 |

<!-- Sources: current migration subpages, v6/v8 tags, WowRuntime, SnapshotStore, Redis/Mongo guards -->
