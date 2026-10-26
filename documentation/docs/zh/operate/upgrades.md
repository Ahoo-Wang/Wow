---
title: 升级部署
description: 用滚动更新升级服务，哪些可以并行运行，格式、状态修订号、事件和 Manifest 怎样让升级保持安全，以及主版本会改变什么。
outline: deep
---

# 升级部署

服务通过滚动更新升级：新节点在旧节点旁边启动，分区移到它们上面，然后旧节点停止。有一段时间，两个版本同时运行，面对同一个数据库和同样的主题。本页说明是什么让这件事保持安全，无论是升级 Wow 还是升级你自己的代码。

## 两个版本并行 {#two-versions-side-by-side}

在同一主版本内，Wow 相邻的次版本可以在一个服务内、也可以跨服务并行运行。它们的内部协议（集群通道、等待信号、线上形式）以及存储格式保持兼容：

- **存储格式**按结构而不是按行做版本：每张日志、快照和定时器表都在 `wow_format` 中记录它的格式。在同一主版本内格式不变；同一格式内新增的列，会在下次检查时加到已有的表上，读者忽略它们不认识的东西。
- **线上形式**（Kafka 信封、命令、回执、等待信号）也带着它们的格式。新的可选字段先由一个次版本开始读取，下一个次版本才开始写入。
- **不兼容的改变**要等下一个主版本，由它的迁移来处理。

所以一次只升一个次版本，并让每次发布完成之后再开始下一次。

## 你的代码：事件 {#your-code-events}

事件的类在你服务的两个版本之间可能改变，而发布期间两个版本都会读取日志：

- 新增一个带默认值的可选字段，什么都不需要。
- 其他任何改变都要提高事件的修订号，并提供一个从旧修订号开始的升级函数（[演进事件](../guide/event-evolution)）。先部署读取的一方：每个节点都能读修订号 `n + 1` 之后，再部署写入它的代码。否则旧节点可能读到一条它没有升级函数的记录。
- 另一个服务中的消费者，在它的 api 升级之前，会把新修订号读成 `PublishedEvent`，也不会为它调用接收这个事件类的处理函数（[发布与消费事件流](../guide/published-streams#events-the-subscriber-has-no-class-for)）。

## 你的代码：状态 {#your-code-state}

状态结构的改变要提高 `@AggregateRoot(stateRevision = n)`。发布期间，新节点以新修订号声明快照表并原地重建它，而旧节点继续以旧修订号写入；存储会阻止旧节点覆盖已重建的快照。回滚时，只有在不再剩下任何更高修订号的节点之后，才会把声明降回去（[重建与重放](./replay#snapshots-raise-the-state-revision)）。

数仓的展平视图遵循同样的规则：写入者只会用更新的定义替换它，所以重启的旧节点不会撤销新节点的视图（[ClickHouse](./clickhouse#schema-management)）。

## 你的代码：契约 {#your-code-the-contract}

服务暴露的命令、事件、状态和错误，是它与客户端和对端之间的契约。Gradle 插件把每次构建的 Manifest 与提交的基线比较，在基线的主版本内，会让破坏客户端的改变失败：删除命令或事件、新增必填字段、删除字段或改变字段类型。用 `updateManifestBaseline` 记录有意的改变，并与代码一起评审（[测试服务](../guide/testing-services#check-the-contract)）。破坏性改变需要服务契约的新主版本，或者在旧命令旁边新增一条命令。

## 发布期间的订阅 {#subscriptions-during-a-rollout}

分区连同它的检查点移到新节点上，新节点的订阅从那里继续。新版本中新增的处理函数按它的种类开始：投影从日志的开头，反应器或 Saga 从末尾。改了名的处理函数是一个新订阅：它按种类开始，就像新的一样。

## 不能改变的设置 {#settings-that-must-not-change}

`wow.log.partitions` 在日志有了条目之后就固定了，一个服务的节点共用它们的存储布局（[配置服务](./configuration#what-must-match-across-nodes)）。

## 主版本 {#a-major-version}

新的主版本可能改变格式和协议。它的节点不会与上一个主版本的节点并行运行：它附带一次迁移，在切换之前或切换期间运行，就像 V10 从 9.x 迁移那样（[从 9.x 迁移](../migrate/)）。

## 从 10.0.0 预发布版升级 {#from-a-10-0-0-pre-release}

10.0.0 的预发布版（`alpha`、`beta`、`rc`）对它存储的数据不做兼容承诺：之后版本的存储格式可能在原地改变。从预发布版升级需要一个全新的数据库；更早的预发布版写下的数据既不迁移，也不读取。

### 10.0.0 之前改名的配置键 {#configuration-keys-renamed-before-10-0-0}

配置面在 10.0.0 之前一次定稿，不保留别名：更早预发布版的键不再绑定任何设置。每一行写明取代它的是什么。

| 预发布版的键 | 10.0.0 |
| --- | --- |
| `wow.query.index.ddl` | 删除：查询索引跟随所在存储的 `ddl`（`wow.stores.<store>.ddl`，否则 `wow.ddl`），`validate` 下缺少索引会拒绝启动 |
| `wow.kafka.topics.ddl` | `wow.kafka.ddl`，未设置时跟随 `wow.ddl`（`auto`）；原来默认是 `validate` |
| `wow.clickhouse.sink.ddl` | `wow.clickhouse.ddl`，未设置时跟随 `wow.ddl` |
| `wow.clickhouse.sink.aggregates.<context>.<aggregate>.history.*` | `wow.aggregates.[<context>.<aggregate>].clickhouse.history.*` |
| `wow.kafka.command-intake` | `wow.kafka.command-intake.enabled` |
| `wow.internal.dev-profiles`、`wow.kafka.topics.dev-profiles` | `wow.dev-profiles` |
| `wow.query.http.*` | `wow.query.budgets.http.*` |
| `wow.query.in-process.*` | `wow.query.budgets.in-process.*` |
| `wow.query.in-process-timeout` | `wow.query.unbounded-timeout` |
| `wow.query.events.mongo-read-preference` | `wow.mongo.query-read-preference`，对快照查询同样生效 |
| `wow.stores.<store>.query.read-preference` | `wow.stores.<store>.options.query-read-preference` |
| `wow.query.count-cache.events-enabled` | `wow.query.count-cache.events.enabled` |
| `wow.snapshot.store`、`wow.snapshot.targets` | `wow.snapshot.stores`；第一个存储的订阅名为 `snapshot` |
| `wow.aggregates.[<context>.<aggregate>].snapshot.store` | `wow.aggregates.[<context>.<aggregate>].snapshot.stores` |
| `wow.subscription.parked.min-backoff`、`wow.subscriptions.[<subscription>].parked.min-backoff` | `…parked.initial-backoff` |
| `wow.http.client-facts` | `wow.http.client-facts.enabled` |
| `wow.http.global-errors` | `wow.http.global-errors.enabled` |
| `wow.standby` | `wow.node.standby` |
