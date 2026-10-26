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
| `EventLog` | 上下文的日志：按分区和位置组织的已提交流，按版本和按请求唯一 | `EventLogSpec` |
| `SnapshotStore` | 每个聚合的最新快照 | `SnapshotStoreSpec` |
| `CheckpointStore` | 每个订阅在每个分区上的位置 | `CheckpointStoreSpec` |
| `LeaseStore` | 哪个节点拥有哪个分区 | `LeaseStoreSpec` |
| `FailureLedger` | 停放的聚合 | `FailureLedgerSpec` |
| `ReservationStore` | 已预留的唯一键 | `ReservationStoreSpec` |
| `TimerStore` | 为稍后安排的命令 | `TimerStoreSpec` |

存储可以保存日志、快照或两者：Elasticsearch 存储保存快照，从不保存日志。在存储之外，`Sink` 把提交复制到别处（`SinkSpec`），topic 传输在上下文之间传递发布的事件流和命令（`TopicTransportSpec`），查询后端提供某个模型的查询（`:query-tck` 中的 `QueryBackendSpec`、`EventQueryBackendSpec` 和 `DatasetQueryBackendSpec`）。

## 提供者 {#the-provider}

后端向宿主提供一个 `StoreProvider`：配置所指明的 `type`（`wow.stores.<name>.type`）、它需要应用提供什么、它是否仅凭应用的资源就可用（`wow.backend: auto` 据此选择），以及 `open`，它为一个配置的实例返回一个 `Store`。`open` 不触碰数据库中的任何东西：存储在每个端口首次使用时才打开它，每个上下文一个日志，并记住由什么创建它的 schema，所以由 `wow.ddl` 决定它们是被创建、被校验还是保持不动。

宿主通过 Java 的 `ServiceLoader` 从模块的 `META-INF/services/me.ahoo.wow.spi.store.StoreProvider` 中找到提供者，也会在应用的 bean 中寻找；bean 会取代类路径上同一类型的提供者。提供者是无状态的：每个存储实例都得到自己的 `Store`，基于它的配置所找到的资源，即一个 `DataSource` 或某个驱动的客户端。

## 传输与 Sink 的提供者 {#transport-and-sink-providers}

topic 传输和 Sink 的发现方式与存储相同。`TransportProvider`（`:spi`，`me.ahoo.wow.spi.transport`）打开承载发布事件流、发往其他上下文的命令及其回执的消息代理：它的 `open` 返回一个 `TopicTransport`，即节点使用的 `TopicProducer`、`PartitionedSource` 和 `TopicAdmin`，并且可以在节点就绪后对节点的命令主题给出警告。`SinkProvider`（`me.ahoo.wow.spi.sink`）打开一个目的地的 Sink：`open` 返回一个 `StreamSinks`，每个发布的事件流一个 `Sink`，其订阅命名为 `<processor>.<aggregate>`。宿主通过 `ServiceLoader` 从 `META-INF/services/me.ahoo.wow.spi.transport.TransportProvider` 和 `META-INF/services/me.ahoo.wow.spi.sink.SinkProvider` 中找到它们，也会在应用的 bean 中寻找；宿主自己不构建任何消息代理或数据仓库专有的东西。Wow 的 Kafka 传输（`kafka`）和 ClickHouse Sink（`clickhouse`）与其他提供者没有区别。

在配置面定稿之前，类型为 `<type>` 的提供者读取 `wow.<type>` 下的键，这些键以相对于该前缀的选项交给它（`bootstrap-servers`、`properties.linger.ms`），由它的 `available` 说明这些键是否配置了它。一个节点最多运行一个传输，并运行所有已配置的 Sink。提供者以 `IllegalArgumentException` 拒绝的设置会使启动失败，并指出对应的键。

## 契约测试 {#the-contract-tests}

后端的正确性就是契约。它的容器测试继承它所实现端口的每个 spec，并提供一个全新的空存储：

<!-- sample: fragment -->

```kotlin
class PostgresEventLogTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog = PostgresFixture.log(partitions, context)
}
```

spec 的每个测试都在自己的上下文上运行，所以每个上下文保留一张表或一个集合的后端会把它们隔离开。查询后端声明它的存储可能不支持的特性，比如 keyset 分页或百分位数，spec 会跳过相应的测试；与过滤语义之间已知的差异按 ID 列出，而不是隐藏起来。`:tck-containers` 以固定的镜像版本启动共享的容器，后端的模块在 `src/integrationTest` 中运行这些套件。

## 规则 {#the-rules}

- 后端依赖 `:spi`，提供查询时还依赖 `:query`；它从不依赖另一个后端或运行时。`:architecture` 保证这一点，新模块要加入 `ALLOWED_PROJECT_DEPENDENCIES`。
- 它记录自己创建的每个结构的格式，并拒绝另一种格式的结构，所以节点永远不会写入它不理解的布局。
- 它是一个已发布的模块：它保留 ABI 转储、一个链接到站点的 README，并在“运维”下为它的运维人员提供一个页面。
- 无法访问的存储报告为 `StorageUnavailableException`，节点把它当作系统性的失败：订阅暂停并重试，不停放任何东西。

PostgreSQL 后端 `backends/postgres` 是可以对照其测试阅读的参照。
