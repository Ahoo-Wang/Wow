# V10 日志与快照

日期：2026-10-11。基线：9.8.0（`v9` 上的 `b5813af56f`）。状态：已实现（实现以代码为准，偏离记在决策的「实现」说明里；2026-10-22 复核分支与路径）。上位文档：[00 目标架构](00-architecture.md)（A1、A6、A11）。相关决定：D4、D10、D11、D12、D13、D25、D28。

## 1. 日志合同

```kotlin
@WowSpi
interface EventLog {
    val partitions: Int
    suspend fun append(stream: EventStreamRecord, transport: Transport? = null): AppendOutcome   // 原子；版本与请求 ID 唯一
    suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome>   // 分区属主的分组提交，逐条结果
    fun read(ref: AggregateRef, fromVersion: Int = 1, toVersion: Int = Int.MAX_VALUE): Flow<EventStreamRecord>
    fun readEntries(ref: AggregateRef, fromVersion: Int = 1, toVersion: Int = Int.MAX_VALUE): Flow<LogEntry>
    suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition?
    suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord?
    suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord?   // 不带聚合类型，有默认实现
    suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail   // 冷加载：尾部加最近请求键，一次往返（D261）
    suspend fun readWithSnapshot(ref: AggregateRef, snapshots: SnapshotStore, earlier: Int): SnapshotTail   // 冷加载：快照加尾部，同库时一条语句（D268），有默认实现
    suspend fun lineage(partition: Int): PartitionLineage   // 分区谱系，D240 预留，有默认实现
    fun tail(partition: Int, after: Long? = null): Flow<LogEntry>   // 有序、可续读、不跳过
    suspend fun head(partition: Int): Long?
}

sealed interface AppendOutcome {
    data class Committed(val position: LogPosition) : AppendOutcome
    data class AlreadyCommitted(val existing: EventStreamRecord) : AppendOutcome   // 同一请求已提交（含版本与请求 ID 同时冲突，审查 A2-1）
    data class Conflict(val currentVersion: Int) : AppendOutcome
    data class Unknown(val cause: Throwable) : AppendOutcome   // 内核按版本槽查明（审查 A1-1）
    data class Rejected(val cause: Throwable) : AppendOutcome   // 后端确定拒绝这条流（审查 A2-5）
}
```

（代码块为示意；这些类型在代码里是普通类，构造函数为 `@InternalWowApi`，经 `of(...)` 创建，D216 ⑤、D227 ②、D228 ①；`Scope`、`Causation`、`AggregateRef`、`AggregateType`、`LogPosition` 等封闭的身份值保留为 data class）

按时间读（`stateAt` 按时间）由内核在 `read` 上截止，不是后端合同的一部分；没有单独的「聚合已存在」结果，创建冲突就是版本 1 的 `Conflict`。

由 TCK 对每个后端强制。**合同里没有可选能力**：9.x 的 Redis 不支持按时间读、Elasticsearch 的请求 ID 去重有跳过点，统一接口掩盖了能力差异（KaeX 的研究结论）；V10 的后端要么完整实现合同，要么不是日志后端。

- **身份与唯一键**：`(aggregateRef, version)`、`(tenant, requestId)`，`aggregateRef` 含租户（9.x 不含，B27）。
- **版本**：从 1 开始，严格加 1。
- **分区**：每个限界上下文一份日志，分区数 P 在上下文内固定（D28）；`slot = floorMod(hash(aggregateRef), 4096)`，`partition = slot mod P`，同一聚合永远在同一分区。槽位数 4096 是格式契约，永不变；P 必须是 2 的幂且整除 4096（默认 64，D240、D246），以便以后按倍数在线分裂。哈希算法本身也是格式契约（FNV-1a 64，哈希键 `context/tenant/id`；不得用 `String.hashCode`），换算法等于重分区。
- **位置**：分区内单调的序号，**序号顺序等于提交可见的顺序**。提交时间与自增 ID 都不满足这一条，因为先分配的事务可能后提交。KaeX mvp 用 CosId 事件流 ID 做扫描游标，在自己的 `EventStore.scanForRecovery` 里记下了这个缺口：分布式 ID 只是趋势递增，晚提交的低 ID 会被分页续扫永久跳过，于是每个持久后端都得另写「回看窗口重扫」。V10 把位置定义成提交可见的序号，从合同上消除这类问题。`tail` 不跳过任何已提交条目，同一位置续读得到相同序列。
- **条目** = 事件流（事实：事件、版本、`commandId`/`requestId`、提交时间）+ 传输元数据（只在等待超出 `COMMITTED` 时写：`WaitContext`；不属于事实，领域代码与查询看不到，Kafka Sink 只把它放进信封的传输段，迁移与重放会丢弃，D25）。

## 2. 后端

| 后端 | 分区序号的分配 | 说明 |
|---|---|---|
| PostgreSQL（D10） | 一条自动提交的语句：CTE 读当前版本，版本恰好落后一位时才给分区计数行加一（行锁持有到提交），再插入；冲突时整条回滚，不留空洞 | JDBC 加虚拟线程（D27）；尾随读按可配置的间隔轮询、空闲时退避（审查 A2-14），`LISTEN/NOTIFY` 未实现；H1：64 分区约 3 万次/秒 |
| MongoDB（D11，必须支持） | 节点从分区计数文档一次预留一段序号（256 个），在内存里依次分配；一次追加是一条 `insertOne`（`w:majority`），不开事务（D271，见下） | 需要副本集（多数派读写）；变更流只做通知，不作为位置来源（受 oplog 窗口限制）；序号递增但有空号 |
| 内存 | 分区内数组 | 测试与示例 |
| Redis（D12） | — | V10 不提供；社区可按本合同与 TCK 自行实现 |

PostgreSQL 上分区内序号分配串行，单分区吞吐约等于「1 / 一次提交的延迟」，P 是吞吐的旋钮（64 分区 × 每分区 1,000 次/秒 = 6.4 万次/秒）。

**MongoDB：属主分配序号，每个位置都要终局**（D271，取代原先的多文档事务与属主追加队列；原型见 `test/benchmarks/results/reports/v10-d271-mongo-sequences-spike.md`）。MongoDB 的事务遇到同一计数文档的并发修改时以写冲突中止（对抗验证 F1、H1），且每次追加 4 次往返；现在一次追加只有一条插入，唯一键 `(aggregate_id, tenant_id, version)`、`(tenant_id, request_id)` 判冲突与重复，`(partition, sequence)` 判位置是否已被占用。可见顺序因此可能与序号顺序不同，日志的读者改为遵守一条规则：**按序号读，从不越过一个还可能写入的序号**。为此每个分出的序号都要终局：写成流，或写一条占位（没有聚合、版本、请求字段，两个唯一键是只覆盖流的部分索引，按聚合的读取、`head` 与 `events` 查询都看不到它）。
- **段**：计数文档记下未封口的段 `{node, from, to, collections}`，`collections` 是段可写的聚合集合；节点只往清单里的集合写，写新集合或需要更大的序号时先换段，旧段的剩余部分写一条区间占位封口。
- **占位**：确知未写（版本冲突、拒绝）时当场写；结果未知时尽快写（流若已落地则占位撞键，保留流）；写不进去时交给尾随补写。
- **崩溃与僵尸**：尾随在一个序号前等待超过宽限期（1 秒）时查段清单：自己未分出的剩余部分就地封口，别的节点的段从该序号起逐号写占位，写入段清单的每个集合，`(partition, sequence)` 拦住那个节点迟到的写入；它的下一次插入撞键后换段重试。
- **同一聚合的顺序**：新流的序号大于该聚合上一条的序号，日志从读取与追加中记住它；跨节点交接后也保持版本顺序。

分区追加队列在 MongoDB 上仍默认开启（D248 ④ 起所有后端都默认开启），但理由变了：不再是为了避免中止，而是负载高时把一个分区的追加按集合合成一次无序 `insertMany`（组提交，每条流各自取号、各自的结局）；空闲时一批只有一条，没有额外开销。本机闭环饱和（10 万账户）：E1 峰值 3,169/s 对 `main` 2,772/s，E3 1,526/s 对 1,238/s，各并发度的 p50 都更低。D11 不再依赖 D6 的属主串行。

**分组提交要逐条隔离**（对抗验证 F2）：PostgreSQL 的唯一约束冲突、MongoDB 的重复键都会中止整个事务。分组提交里一条事件流的版本冲突不能拖垮同组其他流：PostgreSQL 的 `appendAll` 是一条自动提交的语句：逐条检查版本，分区计数器一次加 k，序号连续无空洞，冲突时整句回滚、逐条重试，结果未知时整组 `Unknown`（D248）；追加队列默认开启（D248 ④）；MongoDB 的组提交是每个集合一次有序 `insertMany`（D271、D273）：编码失败或超过文档大小的流单独以 `Rejected` 结束（审查 A2-5、R2.2-2；驱动在发送途中拒绝超大文档时，已落地的成员仍是 `Committed`），位置被封的流单独重试，写入结局未知时整组 `Unknown`（审查 A2-6）。一组里可以有同一聚合版本连续的若干流（热点聚合成批，D273）：它们按前缀提交，一条没有提交，其后的同一聚合的流都是 `Conflict`；PostgreSQL 按链内序号检查版本，MongoDB 的链上序号由前一条给出。**跨分区组提交**（D275）：追加队列每个分区仍然只有一个组在途、分区内顺序不变；声明 `appendsAcrossPartitions` 的日志（MongoDB）由两个合并器轮流把各分区此刻交来的组合成一次 `appendAll`（每次至多 512 条流，`wow.log.append-queue.merge`、`mergers`、`merge-max`），不为凑批而等待；每个分区按出现顺序取自己的序号，一个分区的失败不影响其他分区的结局；MongoDB 每个集合一次有序 `insertMany`，成员按分区排序，失败只让其后的分区改为逐条重追加。中等负载下每个分区的组只有一两条，单独调用时每次都要付存储的固定开销（MongoDB 每次插入调用约 180 µs 的 mongod CPU）。PostgreSQL 暂仍每个分区一次调用。改变 P 需要一次离线重分区，由数据工具完成，不是运行时功能。日志存储可以按聚合配置为不同的日志组（D177，`LogGroupRouter`）；每个日志组内分区数固定。Elasticsearch 不再作为日志后端（D13）。

## 3. 快照：状态即读模型

快照是每个聚合**最新的已提交状态**加元数据（版本、租户、所有者、空间、删除标记、首末事件时间、标签）。一份数据，三处用：

| 用途 | 怎么用 |
|---|---|
| 写侧加速 | 缓存未命中时，快照加尾部重建状态；快照与日志同在一个 PostgreSQL 库时一条语句读出（D268） |
| 默认读模型 | 查询协议直接查快照；能力描述由 Manifest 中的状态 schema 推出，用户不写投影 |
| 视图与数据 | view-engine 的记录、分析视图与数据集默认建在快照上 |

- **写入**：内置订阅 `snapshot`，按版本守卫。同一批里同一聚合的多个版本只写最新的一个（合并写入），等待较早版本 `SNAPSHOT` 的调用方在更新的版本写入后一并完成；写放大因此随聚合数而不是提交数增长。不在追加事务里：快照是派生物，可从日志重建。「写后即可查」等待 `SNAPSHOT` 阶段。分区属主同时执行命令与运行订阅，写快照时状态通常已在本节点缓存里。
- **只是缓存，不是事实**：快照带状态 schema 指纹；指纹不符就丢弃、从事件重建，查询侧蓝绿重建。状态类因此可以自由演进。快照只按版本守卫写入，重建一律写进新的存储，不做同版本原地覆盖（D54）。
- **访问事实与查询同源**：快照元数据里的所有者、空间、标签由准入写入，查询准入按同样的字段名注入过滤条件；字段名只在一处定义（Manifest），两边不各写一份（KaeX mvp 的 `AccessFactsFields`）。
- **按聚合独立配置存储**（D177）：命名存储实例 `wow.stores`，按聚合指定日志与快照的存储；日志按存储实例分组，每组有自己的分区序号，订阅检查点按组记。
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
- ~~分区计数行的热点与「可见水位」两种方案的取舍~~：已定（D250），保持每分区计数器连续；属主分配序号留作 10.x 原型。
- 快照 schema 指纹的计算方式；蓝绿重建快照时读流量的切换。
- 日志的保留与归档（是否允许冷数据分层）。
