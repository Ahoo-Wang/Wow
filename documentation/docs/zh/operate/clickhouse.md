---
title: ClickHouse
description: 用数仓 Sink 把每个聚合的提交写入 ClickHouse，它保存的表与列及其 DDL、集群的建表方式、去重与保留、wow_catalog，以及从中提供数据集。
outline: deep
---

# ClickHouse

ClickHouse 是 Wow 的数仓。一个 Sink 把每个发布的聚合的提交复制进去，ClickHouse 从中推出当前状态和每个事件一行，服务的数据集也在那里查询。日志仍然是唯一的事实：数仓可以删掉，再从日志重新填充。本页连接一个服务，介绍这些表和它们的列，并说明怎样在集群上运行它们。

## 连接服务 {#connect-a-service}

把 `wow-clickhouse` 放到类路径上，设置 `wow.clickhouse.endpoint`（服务器的 HTTP 端点），并用 `wow.clickhouse.sink.enabled: true` 开启 Sink。把每个事件写入数据仓库是服务主动选择承担的开销，所以在该设置开启之前 Sink 一直关闭；端点和客户端都不会开启它。eShop 的 ordering 服务在它的 `clickhouse` profile 中两者都做了：

<!-- sample: examples/eshop/ordering/ordering-server/src/main/resources/application.yaml#clickhouse -->

```yaml
spring:
  config:
    activate:
      on-profile: clickhouse
wow:
  clickhouse:
    # The order stream is written to ClickHouse by the Sink (eshop_ordering_order_state_flat, ...), and the sales
    # dataset, META-INF/wow/dataset/eshop-ordering.sales.json, is queried there: /wow/dataset/eshop-ordering/sales/query.
    endpoint: http://localhost:8123
    sink:
      enabled: true
```

`wow.clickhouse.username`（`default`）和 `password` 用于登录，`wow.clickhouse.database`（`default`）存放这些表。Sink 写入 Manifest 发布的每个聚合。只要有客户端，数据集就会提供服务，除非设置 `wow.clickhouse.query.enabled: false`。

Sink 和数据集按同一条规则取得客户端：应用有 ClickHouse `Client` bean 时用它，Wow 从不关闭它；否则各自用这些设置打开一个客户端，并随节点关闭。开启了 Sink 却两者都没有时，启动失败，并指出这两种方式。想共用一个客户端，或自己也要用 ClickHouse 的应用，把客户端声明为 bean：用 Spring 的 `Binder` 绑定 `wow.clickhouse`，用 `ClickHouseClients.of` 构建客户端，并随自己的上下文关闭它。分析目录服务为读取数据仓库就是这样做的；它的客户端不会开启 Sink：

<!-- sample: platform/analytics/catalog/server/src/main/kotlin/me/ahoo/wow/analytics/catalog/server/AnalyticsCatalogConfiguration.kt#shared-client -->

```kotlin
@Bean(destroyMethod = "close")
@ConditionalOnProperty(prefix = ClickHouseClients.PREFIX, name = ["endpoint"])
fun analyticsCatalogClickHouseClient(environment: Environment): Client {
    val settings = Binder.get(environment)
        .bind(ClickHouseClients.PREFIX, Bindable.mapOf(String::class.java, String::class.java))
        .orElse(emptyMap())
        .orEmpty()
    return ClickHouseClients.of(settings)
}
```

## 表 {#the-tables}

每个聚合类型一组，以它的上下文和聚合命名（`eshop_ordering_order`）：

| 表 | 保存 |
| --- | --- |
| `<ctx>_<agg>_state_events` | Sink 插入的基表：每次提交一行，带着它的事件和提交之后的状态。按提交的月份分区，按租户、聚合和版本排序 |
| `<ctx>_<agg>_state` | 每个聚合的当前状态，由物化视图从基表填充：基表的列，但没有事件与 headers，它们由历史保留 |
| `<ctx>_<agg>_events` | 每个事件一行，由物化视图填充 |
| `<ctx>_<agg>_state_flat` | 当前状态的一个视图，状态的每个顶层标量都是一个有类型的列，供分析工具使用 |

`wow.aggregates.[<context>.<aggregate>].clickhouse.history.enabled: false` 只保留当前状态：Sink 随后直接写 `_state`，也就没有 `_state_events` 和 `_events`。`...history.ttl` 则让历史保留一段时间，而不是永久保留。

## 列与 DDL {#the-columns-and-the-ddl}

`wow.clickhouse.ddl: auto`（默认）时，Sink 在第一次写入之前运行下面这些语句。它们是 `shop` 上下文的 `order` 聚合在 `default` 数据库中的表，历史不设 TTL。一个测试把这个文件与 Sink 实际运行的语句比对，所以本页不会与代码走样：

<!-- sample: backends/clickhouse/src/test/resources/sink/shop_order.sql -->

```sql
CREATE TABLE IF NOT EXISTS `default`.`wow_catalog`
(
    `format` UInt32,
    `database` String,
    `table` String,
    `table_uuid` UUID,
    `role` LowCardinality(String),
    `service` String,
    `context` String,
    `aggregate` String,
    `ownership` LowCardinality(String),
    `tenant_column` String,
    `owner_column` String,
    `space_column` String,
    `sensitive` Array(Tuple(path String, level String, mask String, event String, revision UInt32)),
    `references` Array(Tuple(path String, context String, aggregate String)),
    `event_revisions` Map(String, UInt32),
    `fingerprint` String,
    `columns_hash` String,
    `access` LowCardinality(String),
    `written_at` DateTime64(3, 'UTC') DEFAULT now64(3),
    `writer` String MATERIALIZED currentUser()
)
ENGINE = ReplacingMergeTree(written_at)
ORDER BY (`database`, `table`, fingerprint);

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_events`
(
    `id` String,
    `tenant_id` String,
    `aggregate_id` String,
    `version` UInt32,
    `command_id` String,
    `request_id` String,
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC'),
    `owner_id` String,
    `space_id` String,
    `deleted` Bool,
    `access` String,
    `correlation_id` String,
    `causation_id` String,
    `headers` Map(String, String),
    `operator_id` String,
    `operator_tenant_id` String,
    `operator_cross_scope` Bool,
    `events` Array(Tuple(name LowCardinality(String), revision UInt32, payload String)),
    `state` String,
    `state_fingerprint` String,
    `state_numbers` Map(String, String)
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version)
SETTINGS non_replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state`
(
    `id` String,
    `tenant_id` String,
    `aggregate_id` String,
    `version` UInt32,
    `command_id` String,
    `request_id` String,
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC'),
    `owner_id` String,
    `space_id` String,
    `deleted` Bool,
    `access` String,
    `correlation_id` String,
    `causation_id` String,
    `operator_id` String,
    `operator_tenant_id` String,
    `operator_cross_scope` Bool,
    `state` String,
    `state_fingerprint` String,
    `state_numbers` Map(String, String)
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS non_replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events`
(
    `id` String,
    `tenant_id` String,
    `aggregate_id` String,
    `version` UInt32,
    `sequence` UInt32,
    `event_name` LowCardinality(String),
    `event_revision` UInt32,
    `payload` String,
    `command_id` String,
    `request_id` String,
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC'),
    `owner_id` String,
    `space_id` String,
    `correlation_id` String,
    `causation_id` String
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version, sequence)
SETTINGS non_replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_state_mv` TO `default`.`shop_order_state` AS
(SELECT id, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, owner_id, space_id, deleted, access, correlation_id, causation_id, operator_id, operator_tenant_id, operator_cross_scope, state, state_fingerprint, state_numbers
FROM `default`.`shop_order_state_events`)
COMMENT 'wow-sink-format:1';

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_events_mv` TO `default`.`shop_order_events` AS
(SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, event.revision AS event_revision, event.payload AS payload, command_id, request_id, command_type, commit_time, owner_id, space_id, correlation_id, causation_id
FROM `default`.`shop_order_state_events`
ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence)
COMMENT 'wow-sink-format:1';

CREATE OR REPLACE VIEW `default`.`shop_order_state_flat` AS
SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, `access`, JSONExtractString(state, 'status') AS `status`, JSONExtract(state, 'note', 'Nullable(String)') AS `note`, JSONExtract(state, 'quantity', 'Int64') AS `quantity`, toDecimal128OrZero(state_numbers['total'], 2) AS `total`, toDecimal128OrZero(state_numbers['price'], 0) AS `price`, toDecimal128OrNull(state_numbers['discount'], 18) AS `discount`, JSONExtract(state, 'rate', 'Float64') AS `rate`, JSONExtract(state, 'paid', 'Bool') AS `paid`, JSONExtract(state, 'version', 'Int64') AS `state_version`
FROM `default`.`shop_order_state` FINAL
COMMENT 'wow-sink-format:1 wow-sink-flat:eb326856599fc0e7';
```

排序键就是一行的身份：键与先前某行相同的行，在 ClickHouse 合并时替换它。

| 表 | 引擎 | 分区 | 排序键 |
| --- | --- | --- | --- |
| `_state_events` | `ReplacingMergeTree` | `commit_time` 所在的月 | `(tenant_id, aggregate_id, version)` |
| `_state` | `ReplacingMergeTree(version)`：同一个键保留版本最高的行 | 无 | `(tenant_id, aggregate_id)` |
| `_events` | `ReplacingMergeTree` | `commit_time` 所在的月 | `(tenant_id, aggregate_id, version, sequence)` |
| `wow_catalog` | `ReplacingMergeTree(written_at)` | 无 | `(database, table, fingerprint)` |

聚合的每张表都带 `SETTINGS non_replicated_deduplication_window = N`，N 即 `wow.clickhouse.sink.deduplication-window`。`...history.ttl` 会给 `_state_events` 和 `_events` 加上 `TTL toDateTime(commit_time) + toIntervalSecond(<秒数>)`。不保留历史时只有 `_state` 和 `_state_flat`。

**`_state_events` 与 `_state`**，每次提交一行；`_state` 有除 `headers` 与 `events` 之外的每一列：

| 列 | 保存 |
| --- | --- |
| `id` | 提交的 id，`<聚合引用>@<版本>` |
| `tenant_id`、`aggregate_id` | 聚合的租户和 id；它的上下文和名称是表名的一部分 |
| `version` | 提交之后聚合的版本 |
| `command_id`、`request_id`、`command_type` | 产生这次提交的命令：它的 id、幂等键和类型 |
| `commit_time` | 提交的时间，UTC，精确到毫秒 |
| `owner_id`、`space_id` | 提交之后聚合的作用域，没有则为空 |
| `deleted`、`access` | 聚合是否已删除，以及提交时的访问属性（JSON 文本，含内置的 `owner` 与 `space`）：只作信息，不用于读取的授权 |
| `correlation_id`、`causation_id` | 根命令的 id 和直接原因；调用方直接发出的命令则为空 |
| `headers` | 命令携带的请求事实；仅 `_state_events` 有 |
| `operator_id`、`operator_tenant_id`、`operator_cross_scope` | 谁提交的；没有时为空和 false |
| `events` | 这次提交的事件，每个是 `(name, revision, payload)`，payload 为 JSON 文本；仅 `_state_events` 有 |
| `state`、`state_fingerprint` | 提交之后的状态（JSON 文本）和它的 schema 的指纹；不发布状态的聚合为空 |
| `state_numbers` | 状态每个顶层数字的原文，`_state_flat` 从中读取小数 |

**`_events`**，每个事件一行：`sequence`（事件在它所属提交中的位置，从 1 开始）、`event_name`、`event_revision` 和 `payload`，以及这次提交的 `id`、`tenant_id`、`aggregate_id`、`version`、`command_id`、`request_id`、`command_type`、`commit_time`、`owner_id`、`space_id`、`correlation_id` 和 `causation_id`。

**`_state_flat`** 以 `id`、`tenant_id`、`aggregate_id`、`version`、`commit_time`、`owner_id`、`space_id`、`deleted` 和 `access` 开头，随后是状态 schema 的每个顶层标量属性各一列，顺序与 schema 相同：

| 属性 | 它的列 |
| --- | --- |
| `string`、枚举 | `String` |
| `integer` | `Int64` |
| 带 `x-wow-decimal` 或 `x-wow-money` 的 `number` | `Decimal(38, scale)`：scale 取关键字给出的值，否则取币种的默认小数位数，再否则为 18 |
| 其他 `number` | `Float64` |
| `boolean` | `Bool` |

允许 `null` 的属性是 `Nullable`。对象、数组、引用和组合不生成列；用 ClickHouse 的 JSON 函数从 `state` 读取。与九个系统列重名的属性加前缀 `state_`，示例中的 `version` 就是如此。

## 投递一次，读取一次 {#delivered-once-read-once}

Sink 是一个订阅，所以一次提交可能不止一次到达它：重试、重放、交接。有两个机制让数仓保持精确：

- 每张表记住它最近的 `wow.clickhouse.sink.deduplication-window`（1,000）个插入块，所以重试的插入会被丢弃，视图的表也一样。
- 超出这个窗口时，历史表是以聚合、版本和事件为键的 `ReplacingMergeTree`：重放的行会在合并中去掉，用 `FINAL` 读取时每次提交、每个事件都只计一次。

`wow.clickhouse.sink.batch-size`（1,000）是一次插入最多携带的提交数。

## schema 管理 {#schema-management}

`wow.clickhouse.ddl` 决定 Sink 在第一次写入之前以及之后不时怎样对待它的表：`auto`（默认）创建缺失的部分并校验其余部分；`validate` 只检查；`none` 两者都不做。被删除或被修改过的表会让写入失败，订阅会重试，直到它恢复成 Sink 期望的样子。

Sink 保有的每张表和每个视图都在注释里写明 Sink 表的格式：`wow-sink-format:1`。Sink 期望的表或视图存在、有上面的列、并写明这个格式，才算 Sink 的；否则 Sink 拒绝写入，逐一指出是哪一个、哪里不对（`<table> is not of format 1`、`<table> has the columns …`）。`auto` 用这个注释创建缺失的部分，已存在的表保持原样：别的格式的表会被拒绝，和列不同的表一样。这些表里没有日志之外的东西：删掉它们，重置 Sink 的订阅即可从日志重新填充（[重建数仓](#rebuild-the-warehouse)）。`none` 什么也不检查。

滚动升级期间，一个服务的两个版本可能同时写入两种状态 schema。展平视图在注释里、格式之后，记住创建它所用的定义的指纹（`wow-sink-format:1 wow-sink-flat:<指纹>,…`），写入者只会用更新的定义替换它，所以重启的旧节点不会撤销新节点的视图。

## 在集群上运行 {#run-it-on-a-cluster}

Wow 9.x 默认用 `ON CLUSTER '{cluster}'` 建表：复制的 `_local` 表，前面是一张按租户和聚合 id 分片的分布式表。Wow 10 的 Sink 只建单机表。上面的每条语句只在 Sink 的端点所连的那个节点上运行，没有任何设置能让 Sink 写 `ON CLUSTER`、复制引擎或分布式表。生成集群的 DDL 是 10.x 的候选，不在 10.0 之内。在集群上，请自己用上面的列、视图和键建表，并让 Sink 检查它们。

Sink 按上面的名字插入和读取它的表，所以这些名字是分布式表，每个分片上它们背后是复制的 `_local` 表。视图在每个节点上运行，读取并写入自己分片的本地表。下面的语句是 Sink 的语句，改写成适合定义了宏 `{cluster}`、`{shard}` 和 `{replica}` 的集群，仍是同一个 `shop` 订单：

<!-- sample: backends/clickhouse/src/test/resources/sink/shop_order.cluster.sql -->

```sql
-- Run on any node of the cluster; {cluster}, {shard}, {replica} and {database} are the macros ClickHouse defines for it.
-- The Sink inserts into and reads the Distributed tables, which carry the names it expects; a _local table is one shard's replicated copy.

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_events_local` ON CLUSTER '{cluster}'
(
    `id` String,
    `tenant_id` String,
    `aggregate_id` String,
    `version` UInt32,
    `command_id` String,
    `request_id` String,
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC'),
    `owner_id` String,
    `space_id` String,
    `deleted` Bool,
    `access` String,
    `correlation_id` String,
    `causation_id` String,
    `headers` Map(String, String),
    `operator_id` String,
    `operator_tenant_id` String,
    `operator_cross_scope` Bool,
    `events` Array(Tuple(name LowCardinality(String), revision UInt32, payload String)),
    `state` String,
    `state_fingerprint` String,
    `state_numbers` Map(String, String)
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}')
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version)
SETTINGS replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

-- _state has the columns of _state_events but the events and the headers.
CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_local` ON CLUSTER '{cluster}'
(
    `id` String,
    `tenant_id` String,
    `aggregate_id` String,
    `version` UInt32,
    `command_id` String,
    `request_id` String,
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC'),
    `owner_id` String,
    `space_id` String,
    `deleted` Bool,
    `access` String,
    `correlation_id` String,
    `causation_id` String,
    `operator_id` String,
    `operator_tenant_id` String,
    `operator_cross_scope` Bool,
    `state` String,
    `state_fingerprint` String,
    `state_numbers` Map(String, String)
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}', version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events_local` ON CLUSTER '{cluster}'
(
    `id` String,
    `tenant_id` String,
    `aggregate_id` String,
    `version` UInt32,
    `sequence` UInt32,
    `event_name` LowCardinality(String),
    `event_revision` UInt32,
    `payload` String,
    `command_id` String,
    `request_id` String,
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC'),
    `owner_id` String,
    `space_id` String,
    `correlation_id` String,
    `causation_id` String
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}')
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version, sequence)
SETTINGS replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

-- Every commit of an aggregate goes to one shard: the sharding key is its tenant and id. The Sink reads the format from
-- the comment of the tables it expects, these Distributed ones, which `AS` does not copy.
CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_events` ON CLUSTER '{cluster}'
AS `default`.`shop_order_state_events_local`
ENGINE = Distributed('{cluster}', `default`, `shop_order_state_events_local`, sipHash64(tenant_id, aggregate_id))
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state` ON CLUSTER '{cluster}'
AS `default`.`shop_order_state_local`
ENGINE = Distributed('{cluster}', `default`, `shop_order_state_local`, sipHash64(tenant_id, aggregate_id))
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events` ON CLUSTER '{cluster}'
AS `default`.`shop_order_events_local`
ENGINE = Distributed('{cluster}', `default`, `shop_order_events_local`, sipHash64(tenant_id, aggregate_id))
COMMENT 'wow-sink-format:1';

-- The views run on every node, from its own shard's local tables.
CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_state_mv` ON CLUSTER '{cluster}' TO `default`.`shop_order_state_local` AS
(SELECT id, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, owner_id, space_id, deleted, access, correlation_id, causation_id, operator_id, operator_tenant_id, operator_cross_scope, state, state_fingerprint, state_numbers
FROM `default`.`shop_order_state_events_local`)
COMMENT 'wow-sink-format:1';

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_events_mv` ON CLUSTER '{cluster}' TO `default`.`shop_order_events_local` AS
(SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, event.revision AS event_revision, event.payload AS payload, command_id, request_id, command_type, commit_time, owner_id, space_id, correlation_id, causation_id
FROM `default`.`shop_order_state_events_local`
ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence)
COMMENT 'wow-sink-format:1';

CREATE OR REPLACE VIEW `default`.`shop_order_state_flat` ON CLUSTER '{cluster}' AS
SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, `access`, JSONExtractString(state, 'status') AS `status`, JSONExtract(state, 'note', 'Nullable(String)') AS `note`, JSONExtract(state, 'quantity', 'Int64') AS `quantity`, toDecimal128OrZero(state_numbers['total'], 2) AS `total`, toDecimal128OrZero(state_numbers['price'], 0) AS `price`, toDecimal128OrNull(state_numbers['discount'], 18) AS `discount`, JSONExtract(state, 'rate', 'Float64') AS `rate`, JSONExtract(state, 'paid', 'Bool') AS `paid`, JSONExtract(state, 'version', 'Int64') AS `state_version`
FROM `default`.`shop_order_state` FINAL
COMMENT 'wow-sink-format:1 wow-sink-flat:eb326856599fc0e7';
```

- **设置 `wow.clickhouse.ddl: validate`。** 此时 Sink 不创建任何东西，期望的表或视图缺失、列不同或没有写明 Sink 的格式时拒绝写入，并指出是哪一个。分布式表能通过，因为 ClickHouse 报告它们的列与本地表相同。Sink 从分布式表自己的注释读取格式，`AS` 不会复制注释，所以示例给每一张都写了 `COMMENT`。`auto` 会在端点所连的那一个节点上把缺失的表建成单机表；`none` 什么也不检查。
- **按聚合分片。** 分片键是 `sipHash64(tenant_id, aggregate_id)`，与 9.x 相同，所以一个聚合的所有提交都在同一个分片上：`_state` 保留同一个键版本最高的行，历史表按各自的键合并掉重放的行，两者都只在一个分片之内生效。
- **每个分片一个副本接收写入。** 在集群的分片上设置 `<internal_replication>true</internal_replication>`，与任何复制表一样：分布式插入于是只写分片的一个副本，由副本互相复制。
- **同步插入。** 默认情况下，ClickHouse 对分布式表的插入在行落到接收节点的磁盘后就应答，之后才把它们发往各分片。Sink 把应答当作提交已在数仓中，并推进它的检查点。让 Sink 的用户同步插入，这样应答就表示各分片已收到这些行：

  ```sql
  CREATE SETTINGS PROFILE IF NOT EXISTS wow_sink ON CLUSTER '{cluster}' SETTINGS insert_distributed_sync = 1 TO wow;
  ```

  `wow` 是 `wow.clickhouse.username` 中的用户。

- **去重。** `non_replicated_deduplication_window` 对复制表不适用，对应的设置是 `replicated_deduplication_window`；示例把它设为 Sink 的默认值。`wow.clickhouse.sink.deduplication-window` 只设置 Sink 自己创建的表。
- **展平视图** 就是 Sink 的语句加上 `ON CLUSTER`。它的列来自聚合的状态 schema：要得到另一个聚合的语句，让 Sink 先对一个单机 ClickHouse 创建一次，再读取 `SHOW CREATE VIEW <ctx>_<agg>_state_flat`。
- **`wow_catalog`** 不在示例中。分析目录读取它，Sink 通过端点所连的节点写入它。没有它时，Sink 记录写入失败并继续；要使用分析目录，就按上面的语句，在 Sink 和分析目录都能访问到的地方创建它。

## 命令不写入 {#commands-are-not-written}

Wow 9.x 的 BI 同步还会创建一张命令表 `<ctx>_<agg>_command`，由命令主题填充。Wow 10 不往 ClickHouse 写命令：Sink 复制的是提交，所以被拒绝的命令、或没有产生变化的命令不会留下任何一行。提交对它的命令保留的内容在 `_state_events` 中：`command_id`、`request_id` 和 `command_type`；操作者 `operator_id`、`operator_tenant_id` 和 `operator_cross_scope`；`correlation_id` 和 `causation_id`（Saga 的命令对应它所处理的那次提交）；`headers` 中的请求事实；以及这个命令产生的事件和它们的 payload。命令的正文不保存。要得到命令的历史：

- **从提交中读取**，看哪个命令在什么时候、为谁改变了一个聚合：

  ```sql
  SELECT version, command_type, commit_time, operator_id, request_id
  FROM shop_order_state_events FINAL
  WHERE tenant_id = 't1' AND aggregate_id = 'order-1'
  ORDER BY version
  ```

- **把审计需要的命令信息放进事件。** 命令的业务数据通常会进入事件的 payload，`_events.payload` 按事件保存它。
- **拒绝在发生之处读取**，而不是在数仓中：`wow.command.rejections` 统计它们，拒绝日志列出它们（[健康、指标与追踪](./observability)）。

## wow_catalog {#wow-catalog}

Sink 为它保存的每张表，在数据库的 `wow_catalog` 中追加一行，内容来自 Manifest 而不是表名：表及其角色（`state_events`、`state`、`events`、`state_flat`），它所属的服务、上下文和聚合，聚合的归属以及租户、所有者和空间所在的列，按路径列出的敏感值和引用，它的事件的修订号，以及该聚合的访问策略是否决定它的行（`access`：按服务的访问策略为 `POLICY` 或 `DEFAULT`）。分析工具和分析目录读取它，以了解一张表保存什么、怎样限定范围和脱敏；分析目录的数据集不读取聚合有策略的表。写入它失败只记日志，从不让 Sink 失败。

## 数据集 {#datasets}

服务在它的表之上声明数据集（`META-INF/wow/dataset/<context>.<dataset>.json`），节点在 ClickHouse 中查询它们：例如 ordering 基于它的订单的销售数据集。`wow.clickhouse.query.enabled` 提供它们，默认打开；`wow.clickhouse.query.settings.<name>` 设置每个数据集查询运行时的 ClickHouse 设置，例如 `max_memory_usage`。`wow.query.dataset.max-concurrent` 限制同时运行的数量，这样分析查询就不会占满 Sink 正在写入的数仓。

ClickHouse 无法连接或不能写入时（连接被拒绝或超时、存活副本过少），数据集查询以 503 和 `Retry-After` 回答 `wow.storage.unavailable`，Sink 的订阅暂停它的分区直到 ClickHouse 恢复，不会搁置任何聚合。ClickHouse 达到容量时（同时运行的查询过多、没有空闲连接），查询同样这样回答，Sink 则限流它的分区并退避重试，只有用完背压预算（`wow.subscription.backpressure.max-attempts`）才搁置聚合。

## 重建数仓 {#rebuild-the-warehouse}

Sink 是一个有自己检查点的订阅，所以数仓从来不是任何东西唯一的副本：它总能从日志开头重新填充。
