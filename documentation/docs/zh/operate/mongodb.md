---
title: MongoDB
description: 在 MongoDB 上运行服务，它需要副本集；故障切换、生产部署与分片集群，Wow 创建的集合、查询的读偏好，以及 schema 管理。
outline: deep
---

# MongoDB

在 MongoDB 上，一个数据库保存服务存储的一切：它的日志、快照、定时器，以及它的检查点、租约、失败台账和预留。追加和读取都经过多数派，所以 MongoDB 必须以副本集运行。本页连接一个服务，并说明它依赖什么。

## 连接服务 {#connect-a-service}

把 `wow-mongo` 放到类路径上，并给应用一个同步驱动的 `MongoClient` Bean：宿主随后选择 MongoDB。Wow 的基准测试服务在它的 `mongo` profile 中定义了一个：

<!-- sample: test/benchmarks/server/src/main/kotlin/me/ahoo/wow/benchmark/transfer/TransferServer.kt#mongo -->

```kotlin
/**
 * The benchmark's V10 server: the account and the transfer saga on MongoDB (the `mongo` profile) or PostgreSQL (the
 * `postgres` profile), its state stream published to Kafka (`application.yaml`). A node of a cluster takes its ports
 * and node id from the command line (test/benchmarks/README.md).
 */
@SpringBootApplication(proxyBeanMethods = false)
class TransferServer

/**
 * The `mongo` profile: the application's `MongoClient` on `spring.mongodb.uri`, which selects the MongoDB backend. It
 * takes the application's driver [CommandListener]s: none in the server, the round-trip counter in ComponentBench.
 */
@Configuration(proxyBeanMethods = false)
@Profile("mongo")
class MongoBackend {
    @Bean(destroyMethod = "close")
    fun mongoClient(environment: Environment, listeners: ObjectProvider<CommandListener>): MongoClient =
        MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(environment.getRequiredProperty("spring.mongodb.uri")))
                .apply { listeners.orderedStream().forEach(::addCommandListener) }
                .build(),
        )
}
```

连接字符串要指明副本集，例如 `mongodb://mongo-1,mongo-2,mongo-3/?replicaSet=rs0`。数据写入数据库 `wow.mongo.database`，默认是 `wow`；给每个服务一个自己的数据库。

## 必须使用副本集 {#a-replica-set-is-required}

- **多数派。** 追加由多数派确认（`w: majority`），每次读取，包括订阅的尾随读，都只读取多数派已提交的内容（`readConcern: majority`）。所以订阅永远不会对一次之后因故障切换而回滚的提交采取行动。代价是条目到达尾随读之前，大约要多一次复制往返。多数派无法提交的副本集（例如带仲裁节点且有一个成员宕机），会让尾随读停滞，直到能够提交为止。单机服务器没有多数派；即使是一个开发节点，也要以单成员副本集运行。
- **追加。** 一次追加就是一次插入，不开事务。节点从分区的计数器一次取一段 256 个序号，依次分给它的追加；唯一键拒绝已被占用的版本或请求。节点取了却没有写入的序号会写上占位，尾随读越过它们，所以分区的序号递增、但有空号。同一分区的追加之间没有争用；负载高时，节点把一个分区的追加按集合合成一次插入（`wow.log.append-queue.*`，默认开启）。

`wow.mongo.append-timeout`（默认 5 秒）限制一次追加的插入时长，它低于默认的等待超时，所以在故障期间追加的命令会在它的等待结束之前得到回答。存储的其他每次调用由 `wow.mongo.operation-timeout` 限制（默认 30 秒，单个存储的 `options.operation-timeout` 可覆盖），建表类命令（格式记录、索引、分片）为 5 分钟：在故障切换中或代理之后丢失的回应会让调用以暂时性错误失败，而不是一直挂住。查询保留自己的超时预算。

## 故障切换 {#failover}

主节点让位或失效时，副本集在几秒内选出新的主节点（测试集群上让位约 2 秒；成员不再应答时，最长为 `electionTimeoutMillis`，默认 10 秒）。它对进行中的追加的影响：

- 已确认提交的追加永远不会丢失：多数派已经有它，新的主节点也有它。
- 确认被故障切换打断的追加，结局未知：它可能已经落地，也可能随旧主节点一起回滚。节点一旦能写，就在它的序号上写占位；如果流已经落地，占位撞上它，流保留。命令以结局未知回答，用同一请求 ID 重试时会找到这条流，或者只提交一次。
- 服务端在写入之前就拒绝的追加，以可重试的 `wow.storage.unavailable` 回答；它什么也没写。
- 驱动在故障切换后会重试一次写入（可重试写，默认开启）；选举那几秒里的大多数追加会等到它并提交。
- 租约和检查点不受影响：持有者在新的主节点上续约，租约有效期内没有别的节点能拿走。

后端的集成测试每晚在三节点副本集上运行：八个写入者追加时让主节点让位，以及把它杀掉再重启，然后把分区的尾随读到底（`test/mongo-cluster/cluster.sh test rs`）。

## 生产部署 {#in-production}

- **三个数据成员，不用仲裁节点。** 多数派写入和读取需要三个成员中的两个在线；仲裁节点参与选举但不存数据，一个数据成员宕机时多数派就无法提交，尾随读停滞。把成员放在三个故障域（可用区或机架）。
- **延迟。** 每次追加都等多数派确认，即一次复制往返；成员之间的延迟保持在几毫秒内。用于灾备的远端成员设为 `priority: 0`、`votes: 0`，它既不会成为主节点，也不拖慢多数派。
- **磁盘与缓存。** 日志以追加为主；给成员不会因 `fsync` 停顿的本地 SSD，并让 WiredTiger 缓存（默认为内存的一半减 1 GB）装得下快照集合的工作集和日志的近期条目。
- **oplog。** 按从节点可能离开的最长维护时间（几小时到一天）设定大小：落后出 oplog 的从节点需要全量重新同步。
- **每个服务一个数据库。** 它保存该服务的日志和存储；多个服务可以共用一个副本集或集群。

## 分片集群 {#sharded-clusters}

在分片集群上，服务连接 mongos 路由（`mongodb://mongos-1,mongos-2/`），存储在创建大集合时就给它们分片（决策 D277）：

| 集合 | 分片键 | 原因 |
| --- | --- | --- |
| `wow_log__<context>__<aggregate>` | `{partition: 1}` | 一个分区的条目是一段连续范围；版本键与请求键都以分区开头，因此仍然唯一，按聚合的读取和尾随都只访问一个分片 |
| `wow_snapshot__<context>__<aggregate>` | `{tenant_id: 1, aggregate_id: "hashed"}` | 快照的 `_id` 就是它的键；新聚合分散到各分片，加载只访问一个，单租户的查询只访问该租户的数据块 |
| `wow_timer__<context>__<aggregate>` | `{partition: 1}` | 定时器位于其属主的分区；键（分区，属主，key）以分区开头，所以保持唯一，按属主的读写只访问一个分片，节点的到期读取只访问其分区所在的分片。恢复时读取一个目标的停放定时器会访问所有分片 |

- 新的日志或定时器集合在为空时按每个分区切块，并把各块轮流分到各分片；新的快照集合从一个数据块开始，由均衡器按数据量分散。
- 计数器、各注册表、检查点、租约、台账和预留都很小，不分片，留在数据库的主分片上。
- 连接所用的用户需要分片权限：`clusterManager` 角色，或该数据库上的 `enableSharding` 以及 `splitChunk`、`moveChunk`。
- `wow.mongo.sharding: off`（或某个存储的 `options.sharding: off`）不给集合分片，留给自行分片的运维；`wow.ddl: validate` 和 `none` 也不分片。
- **分区是迁移的最小单位。** 它的数据块不能再切分，分区增长到超过块大小后，均衡器不再移动它。增加分片时，用 `moveChunk`（带 `forceJumbo`）迁移分区，或打开均衡器的 `attemptToBalanceJumboChunks`。默认 64 个分区时，S 个分片各得约 64 / S 个；热点分区就是热点分片。
- 要让各服务使用各自的分片，在服务启动之前设定每个数据库的主分片（`enableSharding` 带 `primaryShard`）。

后端的集成测试和 TCK 每晚也在两分片、每分片三成员的分片集群上运行（`test/mongo-cluster/cluster.sh test sharded`）。

## 集合 {#the-collections}

存储在第一次使用时创建它们的集合和索引，命名方式与 PostgreSQL 相同：

| 集合 | 保存 |
| --- | --- |
| `wow_log__<context>__<aggregate>` | 一个聚合类型的日志，每条已提交的流一个文档，以及没有流占用的序号的占位 |
| `wow_log__<context>__partition` | 上下文日志的每个分区一个计数器文档：已分出的序号，以及尚未封口的段 |
| `wow_log_aggregate` | 每个上下文的日志有哪些聚合集合 |
| `wow_snapshot__<context>__<aggregate>` | 每个聚合一个文档：它最新的快照，`_id` 为 `{t: 租户, a: 聚合 ID}` |
| `wow_snapshot_table` | 每个快照集合保存的状态修订号 |
| `wow_timer__<context>__<aggregate>` | 一种聚合类型的定时命令，每个定时器一个文档，位于其属主的分区 |
| `wow_timer_aggregate` | 每个上下文有哪些定时器集合；到期读取在一次聚合中合并它们 |
| `wow_checkpoint`、`wow_lease`、`wow_lease_member` | 每个订阅进行到哪里，以及哪个节点拥有哪个分区 |
| `wow_failure_ledger`、`wow_reservation` | 停放的聚合，以及预留的唯一键 |
| `wow_format` | 每个结构的格式版本 |

新聚合类型的集合在日志第一次用到它时创建；不需要重启。小数以 `Decimal128` 精确存储；载荷中有 MongoDB 无法精确存储的数字时，追加会被拒绝。

与 PostgreSQL 一样，`wow.ddl` 决定存储是创建它们的集合和索引（`auto`，默认）、只检查它们（`validate`），还是两者都不做（`none`）（[PostgreSQL](./postgresql#manage-the-schema)）。

## 查询与读偏好 {#queries-and-read-preferences}

快照查询和 `events` 查询默认读取主节点，与追加在一起。在大集合上的自由过滤可能会扫描；用读偏好把这类查询发到从节点：

| 键 | 适用于 |
| --- | --- |
| `wow.query.events.mongo-read-preference` | 每个 `events` 查询，例如 `secondaryPreferred` |
| `wow.stores.<name>.query.read-preference` | 一个存储的快照查询和 `events` 查询 |

从节点可能落后于主节点；必须看到某次写入的读取，要改为指定它的版本（[配置项](../reference/configuration#wow-query)）。

## 多个数据库 {#several-databases}

与 PostgreSQL 一样，命名的存储实例把各聚合类型放到不同的数据库或集群上：`wow.stores.<name>.type: mongo`，并指定每个实例使用的 `client` Bean 和 `database`（[PostgreSQL](./postgresql#several-databases)）。两个日志存储不能共用一个数据库。

## 备份 {#backups}

日志是唯一的事实；快照、读模型和数仓都从它重建。把日志集合与检查点、租约、台账、预留和定时器一起，作为数据库的一次一致备份来备份。
