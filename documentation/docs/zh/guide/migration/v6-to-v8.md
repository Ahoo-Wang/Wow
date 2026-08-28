---
title: Wow v6 迁移到 v8
description: 把固定 Wow v6 系统升级到固定 v8 目标，并分别验证源码、运行时、存储、数据与切换。
---

# Wow v6 迁移到 v8

本指南面向已经存在 Wow v6 事件/存储历史的应用，不是滚动更新一个依赖版本。必须固定精确 source/target
tag、commit、build contract、backend layout 与 rollback dataset。

本页示例矩阵使用经仓库对象验证、自洽的 tag：

| 契约 | Source `v6.20.16`（`744d4b1358a3`） | Target `v8.13.1`（`67402d32a76d`） |
|---|---|---|
| `gradle.properties` version | `6.20.16` | `8.13.1` |
| Java toolchain | 17 | 17 |
| Gradle wrapper | 9.4.1 | 9.7.1 |
| Spring Boot | 3.5.11 | 4.1.1 |
| Kotlin / KSP | 2.3.20 / 2.3.6 | 2.4.10 / 2.3.11 |
| CosId / CoAPI / CoCache | 2.15.2 / 1.12.8 / 3.10.5 | 3.2.1 / 2.2.0 / 4.3.0 |

必须同时核验 tag name、`tag^{commit}`、`gradle.properties`、version catalog、wrapper URL/checksum 与实际
部署 dependency graph。不要替换成另一个“latest v6”或当前 `main` 后假设矩阵仍成立。

## 迁移总览

| 阶段 | 门禁 | 必需证据 |
|---|---|---|
| 0. 固定基线 | 范围/源码 | deployed v6 artifact/version、tag commit、绿色测试、存储 inventory、可恢复 backup |
| 1. 对齐平台 | 源码/运行时 | Gradle/JDK/Boot/Jackson/Kotlin/KSP 与第三方 starter 矩阵、dependency report |
| 2. 适配应用 | 源码/运行时 | domain/server/test 编译、重新生成 KSP/OpenAPI/schema、startup/readiness/shutdown |
| 3. 转换存储 | 存储/数据 | 离线 manifest、key/collection/index inventory、checksum、version、request ID、replay |
| 4. 硬切换 | 运行时/数据 | 旧 writer 停止并排空、单个目标实例、隔离读写/replay/query 检查 |
| 5. 生产准入 | 切换 | 已审批 image/revision、真实流量、metric/trace/alert、对账、rollback window |

禁止让新旧 writer 同时操作已变化的存储契约。先在副本演练，再停 ingress、排空所有 v6 writer、取得最终
backup/watermark，只迁移一次并启动一个 v8 实例；验收通过后才扩容。

回滚有两条路径：第一次 v8 生产写入前，重新连接不可变 v6 dataset 与 binary；之后必须先停止 v8，把
新写入反向迁移/重放后才能启动 v6。只恢复切换时 backup 会丢失已接受工作。

## Spring Boot 4 与 Jackson 3

固定 source `v6.20.16` 使用 Spring Boot 3.5.11，目标使用 Boot 4.1.1。应用代码及所有第三方 starter 都
必须审计 Boot 4 模块化与配置变化。本仓库中大部分 Jackson 3 class 从 `com.fasterxml.jackson` 移到
`tools.jackson`；Jackson annotation 保持兼容 annotation namespace。应比较实际源码 import 与已配置
`ObjectMapper` module，不要盲目全局改名。

重点检查：

- 自定义 serializer/deserializer、mix-in、module，以及直接使用 `ObjectMapper`/`JsonNode`；
- Boot auto-configuration import 与 starter 名称；
- Boot 4 已更改 prefix 的远程配置项；
- Mongo/Redis/Elasticsearch client 与 property binding；
- generated OpenAPI/schema 及 downstream client compatibility。

编译成功不能证明 wire compatibility。使用目标 serializer 反序列化代表性 v6 command/event/snapshot，
比较 materialized state 与重新生成的 contract。

- [`v6.20.16` version catalog](https://github.com/Ahoo-Wang/Wow/blob/v6.20.16/gradle/libs.versions.toml)
- [`v8.13.1` version catalog](https://github.com/Ahoo-Wang/Wow/blob/v8.13.1/gradle/libs.versions.toml)
- [Spring Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)

## 通用升级步骤

### 升级步骤

1. 保存 `./gradlew dependencies` 以及针对 Wow、Boot、Jackson、Reactor、Kotlin、storage client 的模块级
   `dependencyInsight` 输出。
2. 固定目标平台，把 wrapper 与 version constraint 作为一个变更评审。
3. 编译 production/test source，只修复实际使用的 public API，不增加推测性 compatibility bridge。
4. 重新生成 KSP metadata、OpenAPI/schema 与 client，并与 consumer 评审 contract diff。
5. 运行 unit/module/integration test，以及真实 startup、readiness、message flow、graceful shutdown。
6. 安排切换前完成下述存储/数据演练与对账。

### 依赖版本更新

通过应用已有的 version-management 机制固定一个目标版本：

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-spring-boot-starter:8.13.1")
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-spring-boot-starter</artifactId>
    <version>8.13.1</version>
</dependency>
```
:::

还要验证 resolved graph；声明版本不能证明每个 feature capability 或 transitive module 都选择了同一 train。

### 破坏性变更检查

实现前先分类每个 finding：

| Finding | 兼容范围 | 必需动作 |
|---|---|---|
| Kotlin type/method 删除或变化 | 源码/JVM binary | 重新编译 caller，只替换实际使用 API |
| JSON/message/schema 变化 | Wire | 用旧 payload 和 downstream consumer 做 contract test |
| lifecycle/config binding 变化 | 运行时 | startup/shutdown 与环境配置测试 |
| key/collection/index/layout 变化 | 存储 | 离线 inventory/migration，禁止混合 writer |
| snapshot/projection/BI 派生 shape 变化 | 数据 | 从权威 event 重建并对账 |

不能从源码兼容推断 wire 或 storage compatibility。

## 统一运行时编排

当前 v8 使用一个 `WowRuntime` 与 `RuntimeComponent` 契约替代独立 dispatcher launcher。这会破坏源码/
运行时扩展，但不改变 event/snapshot/message format。自定义 lifecycle owner、`MessageReceiver` readiness/
admission、Spring Bean destruction 与共享 shutdown setting 应按
[运行时编排迁移](./runtime-orchestration.md) 适配。

必须验证所有 component prepare 完成后才打开 processing、fatal error 停止完整 runtime，以及 graceful
shutdown 关闭 admission 并排空已接收工作。禁止旧 launcher 与 canonical runtime 混用。

## 移除版本化快照检查点

早期 v8 曾存在的 versioned checkpoint 能力不在当前目标中。移除契约包括
`VersionedSnapshotStore`、`VersionIntervalCheckpointStrategy`、`CompositeSnapshotStrategy`、对应
metrics/tracing decorator 与 `SnapshotCheckpointProperties`。`wow.eventsourcing.snapshot.checkpoint.*` 不再
控制运行时，也不会产生 checkpoint metric/span。

Mongo `*_snapshot_checkpoint` collection 不会被目标读取、写入、扫描、迁移或自动删除。切换前完成
inventory 与 backup；为 v6/早期 v8 回滚保留它们，回滚窗口结束后才删除。目标使用只保存 latest snapshot
的 `SnapshotStore`；目标 snapshot 缺失时需要 event replay，如需持久化必须显式 regeneration。

## SnapshotStore 原子保存

当前 `SnapshotStore.save()` 要求对每个 aggregate 执行一次原子 compare-and-write：

- candidate version 大于等于 stored version → 替换完整 snapshot；
- candidate version 低于 stored version → 正常完成但不写入；
- comparison version 必须来自同一个实际写入的 materialized payload。

同版本替换用于让 regeneration 修复 stale payload。客户端先 `load()` 再无条件 write 不符合契约。所有
自定义 `SnapshotStore` 与 backend implementation 都必须审计 CAS、conditional update、transaction 或
等价原子原语。

该规则本身无需重写 snapshot 数据，但旧 writer 会破坏保证，依赖前必须停止。Mongo implementation 使用
要求 MongoDB 5.2+ 的表达式；仓库 TCK 固定 MongoDB 6.0.6。

## Redis EventStore Canonical v2 布局（v8.9.0 引入）

当前 Redis EventStore、SnapshotStore 与 PrepareKey 只使用 canonical v2 key。runtime 不读取也不迁移
不兼容布局。已发布 v6/shared 与 v8.8 bucketed 布局的 event/request-ID/index key 不同，旧 runtime 也
无法读取 v2 新写入。因此 Redis 必须执行离线存储硬切换。

starter 会检查已配置本地 aggregate 的精确 legacy sentinel key，发现后阻止启动。该 guard 有用但不完整：
它不扫描 Redis Cluster、不发现已移除 metadata、不覆盖 direct/custom store、不证明 snapshot-only scope，
也无法发现被 eviction/破坏的 sentinel。启动通过不是迁移证据。

Canonical v2 还要求同一 named aggregate 的 `AggregateId.id` 在所有 tenant 间只有一个 owner，并使用 128
bucket aggregate-ID index。迁移前：

1. 停 ingress 与全部旧 writer，排空 append，取得一致 backup 和最终 event/version baseline；
2. 盘点每个 logical database/cluster primary 的 legacy event ZSET、shared/per-stream request-ID SET、
   aggregate-ID index、snapshot 与 PrepareKey hash；
3. 固定 historical context alias + aggregate name 到目标 canonical scope，解决跨 tenant duplicate ID；
4. 尽量使用空 target namespace/database；与其他数据共库时禁止 `FLUSHDB`；
5. 用单独评审的幂等离线 migrator 和耐久 manifest，记录 source/target key、type、cardinality、checksum、
   status 与最后验证 batch；
6. 保留 event ZSET member/score 与连续 version；从 committed event JSON 生成目标 request-ID set，任何
   source/event symmetric difference 都要报告，不能隐藏；
7. 使用目标 codec 重建全部 128 个 aggregate-ID bucket，并验证 aggregate scan；
8. 验证 ordered checksum、first/last version、request ID、ID index、代表性完整 state replay 与数量；
9. 只在验证后移除/迁出 inventory 中的 legacy key，sentinel 最后删除；回滚期间保持原 dataset 不可变；
10. 启动一个 v8 实例，测试隔离 ID，显式 regeneration/验证 snapshot，再转移受控流量。

只有 manifest 与 source/target checksum 匹配时，partial migration 才可继续。否则清理 target 或使用新的空
scope 重新开始；禁止让应用把半成品当成完成状态。

没有任何 v2 生产写入前，回滚连接未改变的 legacy dataset；写入后必须先反向迁移或重放这些写入，才能
启动 v6。应用代码应依赖公开 `EventStore`、`SnapshotStore`、`PrepareKey`，不要依赖已移除的 Redis key
converter internal。

来源：[`RedisEventStore.kt`](https://github.com/Ahoo-Wang/Wow/blob/v8.13.1/wow-redis/src/main/kotlin/me/ahoo/wow/redis/eventsourcing/RedisEventStore.kt)、
[`EventStreamKeyLayout.kt`](https://github.com/Ahoo-Wang/Wow/blob/v8.13.1/wow-redis/src/main/kotlin/me/ahoo/wow/redis/eventsourcing/EventStreamKeyLayout.kt)、
[`RedisEventSourcingAutoConfiguration.kt`](https://github.com/Ahoo-Wang/Wow/blob/v8.13.1/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/redis/RedisEventSourcingAutoConfiguration.kt)。

## Mongo 所有权保护

目标保留仅含 aggregate name 的 collection name，并增加耐久 `wow_database_metadata` 记录；其中
`boundedContext` owner 使用 layout version `1`。一个 Mongo database 只能属于一个 bounded context。

部署前：

1. 盘点所有 event-stream、snapshot、prepare database，以及 `*_event_stream`、`*_snapshot`、`prepare_*`
   collection；
2. 拆分 aggregate collection 中包含多个 context 的 database；
3. 显式映射 prepare-only database——guard 无法从 legacy prepare document 推断 context，未标记 database
   会被第一个目标 context 认领；
4. 检查受管 index 的 key order、unique、TTL、partial filter、collation、sparse 与 hidden option；
5. 先部署已验证 owner，并保存 marker/collection inventory 证据。

不能编辑/删除 ownership marker 来绕过冲突。先迁移或删除冲突数据；只有明确重新分配空 database 时才
删除 marker。

来源：[`MongoDatabaseContextGuard.kt`](https://github.com/Ahoo-Wang/Wow/blob/v8.13.1/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoDatabaseContextGuard.kt)。

## 验证清单

- [ ] 精确 source/target tag、commit、build version、wrapper、catalog、JDK 与 resolved dependency 已记录
- [ ] source、test、KSP、OpenAPI/schema 与 downstream contract diff 已评审
- [ ] 目标 startup/readiness/message flow/graceful shutdown 与 fatal failure path 通过
- [ ] event、snapshot、Redis、Mongo、PrepareKey、projection 与 BI scope 已盘点
- [ ] 离线 migration manifest、checksum、version、request ID、ID index 与 replay 已对账
- [ ] storage cutover 前全部旧 writer 已停止；一个目标实例通过隔离读写验证
- [ ] metric/trace 与 Collector/backend receipt 覆盖 command、store 与 downstream processing stage
- [ ] production image/revision、真实流量、alert、业务 invariant 与 rollback window 已验证
- [ ] 第一次 v8 写入前后的回滚都有已演练数据步骤

## 相关页面

| 页面 | 关系 |
|---|---|
| [迁移指南](../migration.md) | 范围与证据模型 |
| [运行时编排迁移](./runtime-orchestration.md) | 生命周期源码/运行时迁移 |
| [运行时生命周期](../advanced/runtime-lifecycle.md) | 稳定 v8 运行时语义 |
| [Redis 扩展](../extensions/redis.md) | 当前 Redis 配置与 guard |
| [Mongo 扩展](../extensions/mongo.md) | 当前 Mongo 配置与 ownership |
| [BI 部署与恢复](../bi-operations.md) | BI ownership、对账与 Reset |

<!-- Version facts verified from local v6.20.16 and v8.13.1 tags; storage/runtime facts from current source/tests. -->
