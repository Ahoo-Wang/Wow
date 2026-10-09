---
title: BI 部署与恢复
description: Wow BI 的归属、Deploy、Reset、中断恢复、验收与回滚。
---

# BI 部署与恢复

本手册适用于 BI layout 8。layout 不同的部署不会被原地迁移：DEPLOY 拒绝它，确认后的 RESET 删除并按当前
layout 重建（见[升级](#升级)）。

## 操作边界

一个写者拥有一个物理 BI scope：`database`、`consumerDatabase`、`consumerGroupNamespace` 与 topology。
catalog inspection、脚本生成、审阅和顺序执行必须由同一把外部锁覆盖；generator 不提供分布式锁。

| 操作 | 数据影响 | 必需 inspection |
|---|---|---|
| `DEPLOY` | 创建缺失对象、修复漂移的计算对象、删除不再需要的计算对象与 queue；store 只创建或保留，不删除 | 生产必须使用 ClickHouse inspector |
| `RESET` | 删除并重建本 scope 拥有的全部对象，启动新的 replay generation | 可用的权威 inspection 加 `replayFromEarliestConfirmed=true` |

`wow.bi.script.enabled` 默认是 `true`。必须把 `/wow/bi/script` 作为管理路由保护，或将其关闭。默认 NoOp
inspector 只适合首次/离线预览，不能批准 Reset。

SQL executor 必须保持 statement 顺序，并在第一条错误时停止。禁止并发运行两个脚本，也禁止 catalog
变化后重放旧文件。

## 归属与 anchor

每个 BI 对象的 Comment 以 `wow-bi:` 开头，记录 layout、`deploymentId`、对象种类与所属 aggregate。只有
`deploymentId` 与当前 scope 一致的对象才会被修改或删除；不能根据熟悉的 table name 推断归属。

`__wow_bi_deployment` anchor 是脚本的最后一条语句，记录部署级事实：

- phase（`STABLE` 或 `RESETTING`）、configuration fingerprint、topology fingerprint 与 consumer identity；
- 持久对象清单：已创建的每个 store 与 queue，状态为 `ACTIVE`，或 `RETIRED`（aggregate 已移除、store
  为保留数据而留下）。

持久对象先建后记，清单只会落后于 catalog、不会超前。因此清单里的 store 或 queue 消失，意味着数据或
Kafka offset 已丢失，DEPLOY 会拒绝并要求 RESET，而不是悄悄重建一张空表。

## 操作决策

| 观测到的 catalog 状态 | 操作 | 原因 |
|---|---|---|
| 空目标 scope | `DEPLOY` | 安装 store、ingress、view 与 `STABLE` anchor |
| 当前 scope 且持久契约一致 | `DEPLOY` | 幂等对账：已存在且定义一致的 store、queue、view 与 consumer 都保持不动，脚本只重写 anchor；摄入不暂停 |
| 计算 view/materialized-view 漂移 | `DEPLOY` | 替换漂移的定义；consumer 漂移时暂停并重建该 stream 的整条 consumer 链 |
| 期望的 store/queue 缺失且不在清单中 | `DEPLOY` | 首次创建或中断后补齐 |
| 清单中的 store/queue 缺失 | 备份后确认 `RESET` | 数据或 offset 已丢失 |
| Store、Kafka queue、configuration 或 topology 契约漂移 | 确认 `RESET`（topology 变化需新 scope） | generator 不原地修改持久契约 |
| 对象或 anchor 的 layout 不是当前值 | 确认 `RESET` | 见[升级](#升级) |
| anchor phase 为 `RESETTING` | 用完全相同物理范围配置继续 `RESET` | 复用已记录的 reset consumer identity |
| anchor 为 `STABLE` 但 ingress 不完整 | `DEPLOY` | 重建缺失 queue/consumer materialized view |

## 发布前检查

1. 固定 application/Wow version、BI layout、request options 与 generated client version。
2. 停止该 scope 的全部旧 BI consumer/writer，并获取外部锁。
3. 配置 `wow.bi.script.inspector.type=CLICKHOUSE`；验证 endpoints、credential、timeout 与 replica access。
4. 记录 database、consumer database、namespace、topology、cluster/installation、topic prefix、Kafka
   servers、offset storage 与 configuration fingerprint。
5. 备份/克隆 ClickHouse scope；保存 anchor Comment、对象 DDL、行数、aggregate 最大 version、Kafka
   offset 与 retention 证据。
6. Reset 前证明所需历史仍在，且新 group 会从 earliest 开始；使用 Keeper offset 时验证其前提。
7. 生成 JSON，审阅 `destructive` 和全部 diagnostic，再审阅有序 SQL。任何未解释 diagnostic 都必须停止。

本地 generator/module 检查只能验证代码与确定性 SQL，不能证明 credential、replica 一致、Kafka
retention、真实流量或生产变更准入。

## 执行 Deploy

1. 持锁重新 inspection 并生成 `DEPLOY`，保存请求与 inspection 时间。
2. 严格按响应顺序执行 statement，第一条失败后停止。
3. 中断后丢弃旧脚本，检查新的 catalog 状态，并用完全相同 scope 配置重新生成 `DEPLOY`。每条语句都可
   重跑，重新生成后脚本从当前 catalog 收敛；猜测 statement 续跑点不安全。
4. SQL 完成后再次执行权威 inspection，要求 anchor 为 `STABLE` 且 ingress 完整。
5. 下方验收完成前不得释放外部锁。

## 执行 Reset

Reset 会删除受管 BI scope 内的数据并重放：

1. 取得全量重建的明确审批，确认备份和 Kafka retention，保持所有 consumer 停止。
2. 以 `replayFromEarliestConfirmed=true` 生成 `RESET`，要求 `destructive=true`。
3. 顺序执行；若中断，再次 inspection：
   - anchor 为 `RESETTING` → 使用完全相同 scope/configuration 重新生成 `RESET`；
   - anchor 为 `STABLE` 但缺少 ingress → 生成 `DEPLOY`。
4. Reset 完成后再生成并执行一次新的权威 `DEPLOY`：Reset 的 anchor 写在 Kafka ingress 之前，只记录
   store；这次 DEPLOY 把 queue 记入清单并完成剩余对账。
5. 回滚窗口内保持旧 scope/backup 不可变。

## 验收

只有记录下全部适用证据后才能接受部署：

- anchor 为 `STABLE`，layout、configuration 与 topology fingerprint 与请求一致；
- 所需 store、queue、consumer、public view、expansion view 存在，计算 SQL/`TO` target 一致；
- cluster 每个 replica 的对象结构与 metadata 一致；
- Kafka consumption 持续推进，保留 earliest/latest offset 样本，consumer error 为零；
- command/state/latest/expansion 行数及代表性 aggregate 最大 version 与源对账；
- dashboard、alert 与操作路由授权已针对部署 revision 验证。

本地 build 绿色或 SQL exit code 为零只是其中一项，不等于生产准入。

## 升级

layout 8 自 Wow 9.4.0 起生效。它去掉了 ownership registry，部署级事实与持久对象清单改记在 anchor 上。
旧 layout 的部署不会被原地迁移：

1. 按[执行 Reset](#执行-reset) 的前提确认 Kafka retention 覆盖需要重放的历史。
2. 用 9.4.0 生成并执行确认后的 `RESET`。它识别旧对象的归属并全部删除，再按 layout 8 重建。
3. 按第 4 步再执行一次 `DEPLOY`。
4. 手工删除不再使用的 registry 表：

   ```sql
   DROP TABLE IF EXISTS `<consumerDatabase>`.`__wow_bi_registry_<deploymentId>` [ON CLUSTER '<cluster>'] SYNC;
   ```

## 回滚

必须回滚时：

1. 停止 consumer，重新取得同一 scope lock；
2. 保存切换后的写入/offset 进度；
3. 把旧 application、ClickHouse scope、offset state 与 configuration snapshot 作为一个整体恢复；
4. 按已审批计划对账或明确丢弃切换后的分析数据；
5. 验证恢复后的 reader，再重新开放流量。

旧版本会拒绝 layout 8 的部署，因此回滚到 9.4.0 之前的版本必须同时恢复备份的 ClickHouse scope。

设置 `wow.bi.script.enabled=false` 只移除 route/OpenAPI operation/inspector wiring，不会停止 ClickHouse
Kafka engine、恢复数据或回滚 offset。

生成契约见 [商业智能](./bi)，跨版本门禁见 [Wow v6 迁移到 v8](./migration/v6-to-v8)。

<!-- Sources: BiObservedDeploymentPolicy, BiScriptAssembly (durableInventory), BiObjectMetadata/BiAnchorState,
ClickHouseCatalogReader, ClickHouseBiDeploymentInspector, and related tests -->
