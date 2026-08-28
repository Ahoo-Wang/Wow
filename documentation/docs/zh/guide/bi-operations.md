---
title: BI 部署与恢复
description: Wow BI 的归属、Deploy、Reset、中断恢复、验收与回滚。
---

# BI 部署与恢复

本手册只适用于当前 Wow BI protocol/layout，不会原地迁移旧 registry 或 SQL layout。引入当前 owner 前，
必须归档旧物理范围与 offset。

## 操作边界

一个写者拥有一个物理 BI scope：`database`、`consumerDatabase`、`consumerGroupNamespace` 与 topology。
catalog inspection、脚本生成、审阅和顺序执行必须由同一把外部锁覆盖。内部 ownership registry 让中断的
DDL 可恢复，但它不是分布式锁。

| 操作 | 数据影响 | 必需 inspection |
|---|---|---|
| `DEPLOY` | 创建缺失对象、修复计算对象、恢复受管 pending 工作，并按计划退役/删除受管旧对象 | 生产必须使用 ClickHouse inspector |
| `RESET` | 删除并重建受管当前布局，启动 replay generation | 可用的权威 inspection 加 `replayFromEarliestConfirmed=true` |

`wow.bi.script.enabled` 默认是 `true`。必须把 `/wow/bi/script` 作为管理路由保护，或将其关闭。默认 NoOp
inspector 只适合首次/离线预览，不能批准 Reset。

SQL executor 必须保持 statement 顺序，并在第一条错误时停止。禁止并发运行两个脚本，也禁止 catalog
变化后重放旧文件。

## 操作决策

| 观测到的 catalog/registry 状态 | 操作 | 原因 |
|---|---|---|
| 空目标 scope | `DEPLOY` | 安装 registry、store、ingress、view 与 `STABLE` anchor |
| 当前 scope 且持久契约一致 | `DEPLOY` | 幂等对账 |
| 计算 view/materialized-view 漂移 | `DEPLOY` | 先记录 `PENDING_UPDATE`，替换并验证定义，再回到 `ACTIVE` |
| 受管 `PENDING_CREATE`、`PENDING_UPDATE` 或 `PENDING_DROP` | 重新生成同一 `DEPLOY` | registry 是 write-ahead 恢复证据 |
| 缺失 `ACTIVE`/`RETIRED` 对象或仍存在 `TOMBSTONE` | 备份后确认 `RESET` | catalog 已不符合可恢复归属状态 |
| Store、Kafka queue 或 topology 契约漂移 | 确认 `RESET` | generator 不原地修改这些持久契约 |
| registry engine/Comment/sort key/column 非法或旧 protocol/layout | 归档/删除不兼容 scope 后 `RESET` | 无法信任归属 |
| anchor phase 为 `RESETTING` | 用完全相同物理范围配置继续 `RESET` | 复用已记录的 reset consumer identity |
| anchor 为 `STABLE` 但 ingress 不完整 | `DEPLOY` | 重建缺失 queue/consumer materialized view |

不能根据熟悉的 table name 推断归属。只有通过校验的当前 registry 与 `wow-bi:` metadata 才能授权破坏性
清理。

## 发布前检查

1. 固定 application/Wow version、BI protocol/layout、request options 与 generated client version。
2. 停止该 scope 的全部旧 BI consumer/writer，并获取外部锁。
3. 配置 `wow.bi.script.inspector.type=CLICKHOUSE`；验证 endpoints、credential、timeout 与 replica access。
4. 记录 database、consumer database、namespace、topology、cluster/installation、topic prefix、Kafka
   servers、offset storage 与 configuration fingerprint。
5. 备份/克隆 ClickHouse scope；保存 registry HEAD/entries、anchor Comment、对象 DDL、行数、aggregate 最大
   version、Kafka offset 与 retention 证据。
6. Reset 前证明所需历史仍在，且新 group 会从 earliest 开始；使用 Keeper offset 时验证其前提。
7. 生成 JSON，审阅 `destructive` 和全部 diagnostic，再审阅有序 SQL。任何未解释 diagnostic 都必须停止。

本地 generator/module 检查只能验证代码与确定性 SQL，不能证明 credential、replica 一致、Kafka
retention、真实流量或生产变更准入。

## 执行 Deploy

1. 持锁重新 inspection 并生成 `DEPLOY`，保存请求与 inspection 时间。
2. 严格按响应顺序执行 statement，第一条失败后停止。
3. 中断后丢弃旧脚本，检查新的 catalog 状态，并用完全相同 scope 配置重新生成 `DEPLOY`。
4. SQL 完成后再次执行权威 inspection，要求 anchor 为 `STABLE`、registry HEAD 一致、没有未解释 pending
   状态且 ingress 完整。
5. 下方验收完成前不得释放外部锁。

registry 会在对象 DDL 前持久化 pending mutation，验证后才记录 `ACTIVE`/`TOMBSTONE`。因此重新生成安全，
猜测 statement 续跑点不安全。

## 执行 Reset

Reset 会删除受管 BI scope 内的数据并重放：

1. 取得全量重建的明确审批，确认备份和 Kafka retention，保持所有 consumer 停止。
2. 以 `replayFromEarliestConfirmed=true` 生成 `RESET`，要求 `destructive=true`。
3. 顺序执行；若中断，再次 inspection：
   - anchor 为 `RESETTING` → 使用完全相同 scope/configuration 重新生成 `RESET`；
   - anchor 为 `STABLE` 但缺少 ingress → 生成 `DEPLOY`；
   - registry 不兼容/缺失 → 停止并恢复或人工归档，不能猜测归属。
4. Reset 完成后再生成并执行一次新的权威 `DEPLOY`，完成剩余对账。
5. 回滚窗口内保持旧 scope/backup 不可变。

## 验收

只有记录下全部适用证据后才能接受部署：

- inspector 验证 registry engine、复制路径、sorting key、Comment、完整 column schema、HEAD revision 与
  object snapshot fingerprint；
- anchor 为 `STABLE`，没有未解释 pending entry，也没有 `TOMBSTONE` 对象残留；
- 所需 store、queue、consumer、public view、expansion view 存在，计算 SQL/`TO` target 一致；
- cluster 每个 replica 的对象结构与 metadata 一致；
- Kafka consumption 持续推进，保留 earliest/latest offset 样本，consumer error 为零；
- command/state/latest/expansion 行数及代表性 aggregate 最大 version 与源对账；
- dashboard、alert 与操作路由授权已针对部署 revision 验证。

本地 build 绿色或 SQL exit code 为零只是其中一项，不等于生产准入。

## 回滚

registry 仍处于当前 pending 状态时，应优先使用当前版本完成恢复。旧客户端可能拒绝 protocol/layout 3/7，
或错误理解 pending phase。

必须回滚时：

1. 停止 consumer，重新取得同一 scope lock；
2. 保存切换后的写入/offset 进度；
3. 把旧 application、ClickHouse scope、offset state 与 configuration snapshot 作为一个整体恢复；
4. 按已审批计划对账或明确丢弃切换后的分析数据；
5. 验证恢复后的 reader，再重新开放流量。

设置 `wow.bi.script.enabled=false` 只移除 route/OpenAPI operation/inspector wiring，不会停止 ClickHouse
Kafka engine、恢复数据或回滚 offset。

生成契约见 [商业智能](./bi)，跨版本门禁见 [Wow v6 迁移到 v8](./migration/v6-to-v8)。

<!-- Sources: BiOwnershipRegistry/Plan, ClickHouseOwnershipRegistryRenderer/CatalogReader,
BiScriptAssembly/Operation, ClickHouseBiDeploymentInspector, and related tests -->
