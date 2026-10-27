---
title: 编写后端
description: 为 Wow 添加一个存储后端：存储要实现的 SPI 端口、宿主怎样找到它，以及它必须通过的契约测试。
---

# 编写后端

后端是在一种技术上实现 Wow 存储端口的模块。内置的 PostgreSQL 和 MongoDB 后端与其他后端没有区别：它们实现同样的端口，以同样的方式被找到，通过同样的契约测试。这些端口是 `@WowSpi`：次版本可能改变它们，使用它们需要 `@OptIn(WowSpi::class)`（[API 级别](../reference/api-levels)）。

## 端口 {#the-ports}

存储保存节点的持久状态。它的端口在 `:spi` 中：

| 端口 | 保存 | 契约 |
| --- | --- | --- |
| `EventLog` | 上下文的日志：按分区和位置组织的已提交流，按版本和按请求唯一；每个条目的传输段是不透明的字节，原样保存、原样返回 | `EventLogSpec` |
| `SnapshotStore` | 每个聚合的最新快照 | `SnapshotStoreSpec` |
| `CheckpointStore` | 每个订阅在每个分区上的位置 | `CheckpointStoreSpec` |
| `LeaseStore` | 哪个节点拥有哪个分区 | `LeaseStoreSpec` |
| `FailureLedger` | 停放的聚合 | `FailureLedgerSpec` |
| `ReservationStore` | 已预留的唯一键 | `ReservationStoreSpec` |
| `TimerStore` | 为稍后安排的命令 | `TimerStoreSpec` |

端口的抽象成员是后端必须实现的；带默认实现的成员是可选优化，默认实现本身是正确的，例如 `EventLog.appendAll` 和 `SnapshotStore.saveAll`。有两项能力是可选的，并且显式提供：能在一次往返中同时读出聚合的快照和其后尾部的日志，从 `EventLog.snapshotTails` 返回一个 `SnapshotTailReader`；能原地重建快照的快照存储，从 `SnapshotStore.rebuilds` 返回一个 `SnapshotRebuildStore`（状态修订号变化时的表声明、过期标记与重写）。没有这些能力时，节点分开读取快照和尾部，并保留其他状态修订号的快照，直到有新的提交替换它们。某项能力无法适用或失败时，节点以同样的方式退回，第一次记日志，每次都计数（`wow.store.fallbacks`）。

存储可以保存日志、快照或两者：Elasticsearch 存储保存快照，从不保存日志。在存储之外，`Sink` 把提交复制到别处（`SinkSpec`），topic 传输在上下文之间传递发布的事件流和命令（`TopicTransportSpec`），查询后端提供某个模型的查询（`:query-tck` 中的 `QueryBackendSpec`、`EventQueryBackendSpec` 和 `DatasetQueryBackendSpec`）。

## 提供者 {#the-provider}

后端向宿主提供一个 `StoreProvider`：配置所指明的 `type`（`wow.stores.<name>.type`）、它需要应用提供什么、它是否仅凭应用的资源就可用（`wow.backend: auto` 据此选择），以及 `open`，它为一个配置的实例返回一个 `Store`。`open` 不触碰数据库中的任何东西：存储在每个端口首次使用时才打开它，每个上下文一个日志，并记住由什么创建它的 schema，所以由 `wow.ddl` 决定它们是被创建、被校验还是保持不动。

宿主通过 Java 的 `ServiceLoader` 从模块的 `META-INF/services/me.ahoo.wow.spi.store.StoreProvider` 中找到提供者，也会在应用的 bean 中寻找；bean 会取代类路径上同一类型的提供者。提供者是无状态的：每个存储实例都得到自己的 `Store`，基于它的配置所找到的资源，即一个 `DataSource` 或某个驱动的客户端。

## 传输与 Sink 的提供者 {#transport-and-sink-providers}

topic 传输和 Sink 的发现方式与存储相同。`TransportProvider`（`:spi`，`me.ahoo.wow.spi.transport`）打开承载发布事件流、发往其他上下文的命令及其回执的消息代理：它的 `open` 返回一个 `TopicTransport`，即节点使用的 `TopicProducer`、`PartitionedSource` 和 `TopicAdmin`，并且可以在节点就绪后对节点的命令主题给出警告。`SinkProvider`（`me.ahoo.wow.spi.sink`）打开一个目的地的 Sink：`open` 返回一个 `StreamSinks`，每个发布的事件流一个 `Sink`，其订阅命名为 `<processor>.<aggregate>`。宿主通过 `ServiceLoader` 从 `META-INF/services/me.ahoo.wow.spi.transport.TransportProvider` 和 `META-INF/services/me.ahoo.wow.spi.sink.SinkProvider` 中找到它们，也会在应用的 bean 中寻找；宿主自己不构建任何消息代理或数据仓库专有的东西。Wow 的 Kafka 传输（`kafka`）和 ClickHouse Sink（`clickhouse`）与其他提供者没有区别。

在配置面定稿之前，类型为 `<type>` 的提供者读取 `wow.<type>` 下的键，这些键以相对于该前缀的选项交给它（`bootstrap-servers`、`properties.linger.ms`），由它的 `available` 说明这些键是否配置了它。一个节点最多运行一个传输，并运行所有已配置的 Sink。提供者以 `IllegalArgumentException` 拒绝的设置会使启动失败，并指出对应的键。

## 契约测试 {#the-contract-tests}

后端的正确性由契约决定，而不是由它自己的测试决定。TCK 为每个端口提供一个抽象 spec，后端针对一个全新的空存储运行它所实现端口的 spec 来证明自己。Wow 自己的后端合并前必须通过这些 spec，仓库之外的后端也以同样的 spec 衡量。

### 为你的后端运行 TCK {#run-the-tck}

1. **把 TCK 加入测试依赖。** 三个制品都已发布，版本由 BOM 管理：`wow-tck` 包含存储、Sink 和传输的 spec，不需要 Docker；`wow-query-tck` 包含查询后端的 spec；`wow-tck-containers` 以固定的镜像版本启动共享的容器，供测试需要其中某个容器的后端使用。

   <!-- sample: fragment -->

   ```kotlin
   dependencies {
       testImplementation(platform("me.ahoo.wow:wow-bom:<version>"))
       testImplementation("me.ahoo.wow:wow-tck")
       testImplementation("me.ahoo.wow:wow-query-tck") // when it serves queries
       testImplementation("me.ahoo.wow:wow-tck-containers") // when it runs on one of Wow's containers
   }
   ```

2. **通过 `StoreTck` 运行整个存储。** 给它一个存储的配置，测试工具会通过你的 `StoreProvider` 打开它（按类型经 `ServiceLoader` 找到，和宿主的做法一样），并对存储打开的每个端口运行对应的 spec：存储保存日志时运行 `EventLogSpec`，保存快照时运行 `SnapshotStoreSpec`，只要保存日志就运行控制面（定时器、检查点、租约、失败账本、预留）。不保存日志的存储必须以一个点出它名字的 `UnsupportedOperationException` 拒绝日志和控制面，测试工具会检查这一点。提供者忘记打开或打开错的端口会在这里失败，而以后端自己的类来喂的单端口 spec 看不到这种错误。测试工具通过存储读取时间（`StoreConfig.clock`），所以要把 `config.clock` 传给每个读取当前时间的端口。报告以后端的类和端口命名，例如 `PostgresStoreTck > EventLog > ...`。

   <!-- sample: fragment -->

   ```kotlin
   class PostgresStoreTck : StoreTck() {
       override fun config(): StoreConfig = StoreConfig.of(
           name = "tck",
           type = PostgresStoreProvider.TYPE,
           resources = StoreResources.of(mapOf("dataSource" to PostgresFixture.dataSource)),
       )
   }
   ```

   类路径上没有登记的提供者，覆盖 `provider(type)`；`refused(stream)` 的用法与日志的 spec 相同。

3. **每个端口继承一个 spec。** 每个 spec 都是一个抽象的 JUnit 5 类：实现它的抽象成员，把一个全新的存储交给 spec，别的什么都不用做。它的测试是 final 的，所以后端不能修改或关闭其中某个测试。端口是 `@WowSpi`，所以测试类要用 `@OptIn(WowSpi::class)` 选择加入。PostgreSQL 日志的测试就是一个日志所需的全部，外加两个可选钩子，用来运行快照尾读和被拒绝的流的测试：

   <!-- sample: fragment -->

   ```kotlin
   class PostgresEventLogTest : EventLogSpec() {
       override fun createLog(context: String, partitions: Int): EventLog = PostgresFixture.log(partitions, context)

       override fun createSnapshots(context: String): SnapshotStore =
           PostgresSnapshotStore(PostgresFixture.dataSource, context)

       /** A `\u0000` in a payload string: PostgreSQL's `jsonb` does not take it. */
       override fun refused(stream: EventStreamRecord): EventStreamRecord = stream.copyWith(
           events = listOf(EventRecord("x.aggregate.Named", 1, """{"name":"a\u0000b"}""")),
       )
   }
   ```

4. **让每个测试有自己的存储空间，或者把它们隔开。** 日志和快照的 spec 给每个测试一个自己的上下文，所以每个上下文保留一张表或一个集合的后端会把它们隔离开；检查点的 spec 每个测试用一个自己的组。定时器的 spec 在一个测试中多次调用 `createStore()`，并期望每次调用都给出同一份存储空间上的存储：返回同一个存储，或者每次在同一个数据库上返回一个。

5. **在 CI 中运行它们。** Wow 的后端在 `integrationTest` 源集中针对 `WowContainers` 运行这些 spec（`postgres`、`mongo`、`kafka`、`clickHouse`、`elasticsearch`，各启动一次并共享；`newPostgres()` 等函数给出一个未启动的容器）。使用自己容器的后端以同样的方式运行。

| Spec | 制品 | 需实现 | 可选 |
| --- | --- | --- | --- |
| `StoreTck` | `wow-tck` | `config()`：后端类型的一个存储 | `provider(type)`、`refused(stream)` |
| `EventLogSpec` | `wow-tck` | `createLog(context, partitions)` | `createSnapshots(context)`、`refused(stream)` |
| `SnapshotStoreSpec` | `wow-tck` | `createStore(context)` | |
| `CheckpointStoreSpec` | `wow-tck` | `createStore()` | |
| `LeaseStoreSpec` | `wow-tck` | `createStore(clock)`：spec 会拨动时钟 | |
| `FailureLedgerSpec` | `wow-tck` | `createLedger()` | |
| `ReservationStoreSpec` | `wow-tck` | `createStore(clock)` | |
| `TimerStoreSpec` | `wow-tck` | `createStore()` | `context`、`logPartitions` |
| `SinkSpec` | `wow-tck` | `createSink()`、`written(ref)`：目标处保存的版本 | `aggregateType`、`record(ref, version)`、`visibleWithin` |
| `TopicTransportSpec` | `wow-tck` | `producer`、`source`、`newTopic(partitions)` | `readTimeout` |
| `QueryBackendSpec` | `wow-query-tck` | `store(snapshots)`、`binding(type)` | `semanticDivergences` |
| `EventQueryBackendSpec` | `wow-query-tck` | `store(streams)`、`binding(type)` | |
| `DatasetQueryBackendSpec` | `wow-query-tck` | `createDatasetQueryBackendFactory()`、`createTable(table)` | `semanticDivergences` |

为后端提供的每种存储类型运行 `StoreTck`，再用单端口的 spec 覆盖只有它们能到达的部分：后端自己的钩子。其余的 spec，运行后端所实现端口的，而不是全部。在 Wow 自己的后端中，PostgreSQL 和 MongoDB 运行七个存储 spec、`QueryBackendSpec` 和 `EventQueryBackendSpec`；Elasticsearch 运行 `SnapshotStoreSpec`、`SinkSpec` 和两个查询 spec；Kafka 运行 `TopicTransportSpec`；ClickHouse 运行 `SinkSpec` 和 `DatasetQueryBackendSpec`。

### 能力与跳过的测试 {#capabilities}

后端在端口本身上声明它能做什么，spec 通过一个说明原因的 JUnit 假设，跳过它不能做的那些测试：

- **存储的可选能力。** `EventLog.snapshotTails`（一个 `SnapshotTailReader`）让快照尾读的测试经由它运行，它必须能服务 `createSnapshots` 返回的存储；没有它时，spec 像节点一样分开读取快照和尾部，没有 `createSnapshots` 时则跳过这些测试。`EventLog.appendsAcrossPartitions` 让跨分区的单次 `appendAll` 测试运行。`SnapshotStore.rebuilds`（一个 `SnapshotRebuildStore`）让重建的测试运行。
- **存储拒绝什么。** `refused` 返回一个后端的存储无法保存的流，比如 PostgreSQL `jsonb` 字符串中的 NUL，spec 期望它的追加被拒绝（`AppendOutcome.Rejected`），并且在 `appendAll` 中只有它单独失败；没有它时，spec 跳过这个测试。
- **查询后端的存储。** 绑定的存储事实说明存储对哪些特性是原生支持、部分支持还是不支持：keyset 分页、无界流、百分位数、时间维度聚合等。存储不支持的特性，spec 会跳过它的测试，并说明是哪个事实、取什么值导致的跳过：`Skipped: the storage declares storage.paging.keyset = NONE (keyset pages).`与过滤语义之间已知的差异按用例 ID 列在 `semanticDivergences` 中，它的测试作为已知差异被跳过，而不是隐藏起来。

### 从 9.x 的 TCK 迁来 {#from-9x}

9.x 有消息总线和分发器，以及它们自己的 spec。V10 没有总线：订阅读取日志，上下文之间的流量经过主题传输和 Sink。从 9.x 移植的后端改为运行这些 spec：

| 9.x spec | V10 |
| --- | --- |
| `MessageBusSpec`、`CommandBusSpec` | `TopicTransportSpec`，用于上下文之间的传输。服务内部的命令经过集群通道，它不是端口，没有 spec |
| `DomainEventBusSpec`、`StateEventBusSpec` | 已去掉：订阅读取日志，由 `EventLogSpec` 和 `CheckpointStoreSpec` 覆盖；上下文之间由 `SinkSpec` 和 `TopicTransportSpec` 覆盖 |
| `CommandGatewaySpec`、`CommandDispatcherSpec`、`ProjectionDispatcherSpec` | 已去掉：网关和分发器在运行时内部，不是端口；它们用到的存储由上面的 spec 覆盖 |
| `EventStoreSpec` | `EventLogSpec` |
| `SnapshotStoreSpec` | `SnapshotStoreSpec`（`createStore(context)`） |
| `PrepareKeySpec` | `ReservationStoreSpec` |
| `SnapshotQueryBackendSpec`、`EventStreamQueryBackendSpec`、`DatasetQueryBackendSpec` | `QueryBackendSpec`、`EventQueryBackendSpec`、`DatasetQueryBackendSpec` |
| `StateAggregateRepositorySpec`、`StateAggregateFactorySpec`、`SnapshotStrategySpec`、`DomainEventStreamSpec` | 已去掉：内核内部实现，不是端口 |
| Testcontainers 夹具 | `wow-tck-containers`（`WowContainers`），不含 Redis |

`CheckpointStoreSpec`、`LeaseStoreSpec`、`FailureLedgerSpec`、`TimerStoreSpec` 和 `SinkSpec` 是 V10 新增的：9.x 的后端没有实现这些端口中的任何一个。

## 规则 {#the-rules}

- 后端依赖 `:spi`，提供查询时还依赖 `:query`；它从不依赖另一个后端或运行时。`:architecture` 保证这一点，新模块要加入 `ALLOWED_PROJECT_DEPENDENCIES`。
- 它记录自己创建的每个结构的格式，并拒绝另一种格式的结构，所以节点永远不会写入它不理解的布局。
- 它是一个已发布的模块：它保留 ABI 转储、一个链接到站点的 README，并在“运维”下为它的运维人员提供一个页面。
- 无法访问的存储报告为 `StorageUnavailableException`，节点把它当作系统性的失败：订阅暂停并重试，不停放任何东西。

PostgreSQL 后端 `backends/postgres` 是可以对照其测试阅读的参照。
