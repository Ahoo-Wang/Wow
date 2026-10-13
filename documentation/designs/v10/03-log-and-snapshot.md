# V10 日志与快照

日期：2026-10-11。基线：`main` `b5813af56f`。状态：草案。上位文档：[00 目标架构](00-architecture.md)（A1、A6、A11）。相关决定：D4、D10、D11、D12、D13、D25、D28。

## 1. 日志合同

```kotlin
@WowSpi
interface EventLog {
    suspend fun append(request: AppendRequest): AppendOutcome   // 原子；版本与请求 ID 唯一
    fun read(ref: AggregateRef, versions: VersionRange = VersionRange.ALL): Flow<EventStream>
    fun read(ref: AggregateRef, until: Instant): Flow<EventStream>   // 按事件时间截止
    fun tail(partition: LogPartition, from: LogPosition?): Flow<LogEntry>   // 有序、可续读、不跳过
    val partitions: Int
}

sealed interface AppendOutcome {
    data class Committed(val stream: EventStream, val position: LogPosition) : AppendOutcome
    data class AlreadyCommitted(val stream: EventStream) : AppendOutcome
    data class Conflict(val currentVersion: Version) : AppendOutcome
    data class DuplicateAggregate(val ref: AggregateRef) : AppendOutcome
    data class Unknown(val cause: Throwable) : AppendOutcome
}
```

由 TCK 对每个后端强制。**合同里没有可选能力**：9.x 的 Redis 不支持按时间读、Elasticsearch 的请求 ID 去重有跳过点，统一接口掩盖了能力差异（KaeX 的研究结论）；V10 的后端要么完整实现合同，要么不是日志后端。

- **身份与唯一键**：`(aggregateRef, version)`、`(tenant, requestId)`，`aggregateRef` 含租户（9.x 不含，B27）。
- **版本**：从 1 开始，严格加 1。
- **分区**：每个限界上下文一份日志，分区数 P 在上下文内固定（D28）；`partition = hash(aggregateRef) mod P`，同一聚合永远在同一分区。哈希算法本身是格式契约（跨 JVM、跨版本固定，例如 FNV-1a 64；不得用 `String.hashCode`），换算法等于重分区。
- **位置**：分区内单调的序号，**序号顺序等于提交可见的顺序**。提交时间与自增 ID 都不满足这一条，因为先分配的事务可能后提交。KaeX mvp 用 CosId 事件流 ID 做扫描游标，在自己的 `EventStore.scanForRecovery` 里记下了这个缺口：分布式 ID 只是趋势递增，晚提交的低 ID 会被分页续扫永久跳过，于是每个持久后端都得另写「回看窗口重扫」。V10 把位置定义成提交可见的序号，从合同上消除这类问题。`tail` 不跳过任何已提交条目，同一位置续读得到相同序列。
- **条目** = 事件流（事实：事件、版本、`commandId`/`requestId`、提交时间）+ 传输元数据（只在等待超出 `COMMITTED` 时写：`WaitContext`；不属于事实，领域代码与查询看不到，Kafka Sink 只把它放进信封的传输段，迁移与重放会丢弃，D25）。

## 2. 后端

| 后端 | 分区序号的分配 | 说明 |
|---|---|---|
| PostgreSQL（D10） | 一条自动提交的语句：CTE 读当前版本，版本恰好落后一位时才给分区计数行加一（行锁持有到提交），再插入；冲突时整条回滚，不留空洞 | JDBC 加虚拟线程（D27）；`LISTEN/NOTIFY` 只做通知；H1：64 分区约 3 万次/秒 |
| MongoDB（D11，必须支持） | 多文档事务内递增分区计数文档；由分区属主在本地串行分配（见下），吞吐不足时分组提交 | 需要副本集；变更流只做通知，不作为位置来源（受 oplog 窗口限制）；吞吐约为 PostgreSQL 的六分之一，且依赖 Partitioned，高吞吐场景推荐 PostgreSQL（D52） |
| 内存 | 分区内数组 | 测试与示例 |
| Redis（D12） | — | V10 不提供；社区可按本合同与 TCK 自行实现 |

分区内序号分配串行，单分区吞吐约等于「1 / 一次提交的延迟」，P 是吞吐的旋钮（64 分区 × 每分区 1,000 次/秒 = 6.4 万次/秒）。

**串行放在属主本地，而不是数据库锁上**（对抗验证 F1）：MongoDB 事务遇到同一文档的并发修改时不排队，而是以写冲突中止，由客户端重试；多个节点同时向一个分区追加，计数文档上会出现中止风暴，吞吐塌缩而不是排队。所以分区序号的分配依赖 Partitioned 路由（D6）：稳态下一个分区只有它的属主在追加，属主用一个本地的分区追加队列串行提交，数据库上没有争用；只在属主交接或降级为 Local 时才有冲突，此时靠重试保证正确。这让 D11 依赖 D6：H2 若推翻 Partitioned 默认值，MongoDB 后端的吞吐必须重新评估。H1 的实测（`wow-benchmarks/results/reports/v10-h1-mongo-partition-sequence.md`）：同一分区 32 个写者并发追加只有约 100 次/秒、p99 1.4 秒；同样的负载交给属主排队、一次事务提交一批（`EventLog.appendAll`），约 5.6 千次/秒。

**分组提交要逐条隔离**（对抗验证 F2）：PostgreSQL 的唯一约束冲突、MongoDB 的重复键都会中止整个事务。分组提交里一条事件流的版本冲突不能拖垮同组其他流：PostgreSQL 每条用保存点，MongoDB 在冲突时把该组拆回逐条提交。改变 P 需要一次离线重分区，由数据工具完成，不是运行时功能。一个上下文只有一个日志后端；KaeX mvp 允许每个聚合选不同的事件存储（ADR-0003），V10 不支持，需要不同存储的聚合应拆到不同上下文。Elasticsearch 不再作为日志后端（D13）。

## 3. 快照：状态即读模型

快照是每个聚合**最新的已提交状态**加元数据（版本、租户、所有者、空间、删除标记、首末事件时间、标签）。一份数据，三处用：

| 用途 | 怎么用 |
|---|---|
| 写侧加速 | 缓存未命中时，快照加尾部重建状态 |
| 默认读模型 | 查询协议直接查快照；能力描述由 Manifest 中的状态 schema 推出，用户不写投影 |
| 视图与数据 | view-engine 的记录、分析视图与数据集默认建在快照上 |

- **写入**：内置订阅 `snapshot`，按版本守卫。同一批里同一聚合的多个版本只写最新的一个（合并写入），等待较早版本 `SNAPSHOT` 的调用方在更新的版本写入后一并完成；写放大因此随聚合数而不是提交数增长。不在追加事务里：快照是派生物，可从日志重建。「写后即可查」等待 `SNAPSHOT` 阶段。分区属主同时执行命令与运行订阅，写快照时状态通常已在本节点缓存里。
- **只是缓存，不是事实**：快照带状态 schema 指纹；指纹不符就丢弃、从事件重建，查询侧蓝绿重建。状态类因此可以自由演进。快照只按版本守卫写入，重建一律写进新的存储，不做同版本原地覆盖（D54）。
- **访问事实与查询同源**：快照元数据里的所有者、空间、标签由准入写入，查询准入按同样的字段名注入过滤条件；字段名只在一处定义（Manifest），两边不各写一份（KaeX mvp 的 `AccessFactsFields`）。
- **日志的物理存储也按（上下文，聚合）划分**（D162）：每个聚合类型一张日志表 `wow_log__<ctx>__<agg>`；分区、序列号与租约仍按上下文，尾部读取按 `(partition, sequence)` 归并各聚合表。
- **存储按（上下文，聚合）划分**（D161）：每个聚合类型一张快照表（或一个集合）`wow_snapshot__<ctx>__<agg>`，主键 `(tenant_id, aggregate_id)`：类型转换不会落在别的聚合的行上，权限、CDC 与重建都按聚合。事件日志仍按上下文分区。
- **按聚合可关闭**：只写不查、状态很大的聚合可以不写快照。
- **重建、修复与回填**（D172）：写侧不需要重建；读侧在状态修订号（`stateRevision`）升高时就地重建，个别快照用 `wow.snapshot.Rebuild` 修复，换快照存储时新目标从日志回填、追平后切换 `primary`；期间查询带 `rebuilding: true`。
- **后端**：快照存储即查询后端（Mongo、Elasticsearch、PostgreSQL JSONB），可与日志放在不同后端。

## 4. 历史状态

9.x 提供按版本加载、按时间加载与逐事件追溯的状态路由（`StateRouteContributor` 的 `STATE_VERSIONED`、`STATE_TIME_BASED`、`STATE_TRACING`），V10 保留（D40）：

- 最新状态读快照；历史状态（某个版本、某个时刻、逐事件追溯）从日志重放。快照只保存最新版本，但它在所求的时刻之前已经提交（由日志确认）时，可以从它开始、只读其后的尾部，否则从版本 1 重放；`?at=` 与追溯的基础状态都这样做，与按版本的 `stateAt(version)` 一致（Ahoo-Wang/Wow#4317）。
- 「某个时刻的状态」以提交时间随版本递增为前提。
- 重放有成本，历史状态查询有超时与最大版本数的保护，并进观测指标。

## 5. 待细化

- 各后端的表/集合结构与索引；租约、检查点、失败台账的存储结构。
- 分区计数行的热点与「可见水位」两种方案的取舍（H1）。
- 快照 schema 指纹的计算方式；蓝绿重建快照时读流量的切换。
- 日志的保留与归档（是否允许冷数据分层）。
