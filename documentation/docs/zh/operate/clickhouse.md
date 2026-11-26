---
title: ClickHouse
description: 用数仓 Sink 把每个聚合的提交写入 ClickHouse，它保存的表与列及其 DDL、集群的建表方式、去重与保留、wow_catalog，以及从中提供数据集。
outline: deep
---

# ClickHouse

ClickHouse 是 Wow 的数仓。一个 Sink 把每个发布的聚合的提交复制进去，ClickHouse 从中推出当前状态和每个事件一行，服务的数据集也在那里查询。日志仍然是唯一的事实：数仓可以删掉，再从日志重新填充。本页连接一个服务，介绍这些表和它们的列，并说明怎样在集群上运行它们。

::: warning wow-clickhouse 在 10.0 中是实验性的
`wow-clickhouse` 仍在孵化中：在数据集与分析模块正式发布之前，它可能不经弃用周期而改变。它的类型（`ClickHouseClients`、`ClickHouseSinkProvider`、`ClickHouseSinkOptions`、`AggregateSinkOptions`、`ClickHouseDatasetBindings`、`ClickHouseDatasetOptions`）都是 `@ExperimentalWowApi`（[API 级别](../reference/api-levels)），`wow.clickhouse.*` 和 `wow.aggregates.[<context>.<aggregate>].clickhouse.*` 配置键也可能随之改变，分析目录的镜像也是如此。只靠依赖和配置键开启 Sink 或数据集的服务无需再做什么；在 Kotlin 代码里用到这些类型时（例如用 `ClickHouseClients.of` 构建客户端 Bean），以 `@OptIn(ExperimentalWowApi::class)` 选择使用。数据集与分析模块正式发布后，一个 10.x 的次版本会去掉这个标记。这个标记不改变 Sink 存储的任何数据，也不改变数据集查询发送的任何内容。
:::

## 连接服务 {#connect-a-service}

服务器须为 ClickHouse 25.3 或更新版本。从 25.3 起，服务器会把数据集的租户条件带进它所连接的每个合并（`FINAL`）来源，租户的查询只读该来源中这个租户的行；更旧的服务器每次查询都会读整个来源。

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
@OptIn(ExperimentalWowApi::class) // wow-clickhouse is incubating
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
| `<ctx>_<agg>_state` | 每个聚合的当前状态，由物化视图从基表填充：基表的列，但没有事件与 headers，它们由历史保留；以及 ClickHouse 由它们计算的访问列与展平列 |
| `<ctx>_<agg>_events` | 每个事件一行，由物化视图填充 |
| `<ctx>_<agg>_state_flat` | 当前状态的一个视图，带它的展平列：状态的每个顶层标量都是一个有类型的列，供分析工具使用 |

`wow.aggregates.[<context>.<aggregate>].clickhouse.history.enabled: false` 只保留当前状态：Sink 随后直接写 `_state`，也就没有 `_state_events` 和 `_events`。`...history.ttl` 则让历史保留一段时间，而不是永久保留。

这些表按写入时的样子保存每个状态和事件，其中的 [`@Sensitive`](../guide/queries#where-masking-stops) 值没有脱敏：经 Wow 的读取在返回时脱敏（数据集按[持有状态受保护值](../guide/datasets#protection)的列，以及其声明里标为 `sensitive` 的字段），但直接查询这些表的人读到的是原始值。数据库的授权就是边界。

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
    `id` String CODEC(ZSTD(1)),
    `tenant_id` String CODEC(ZSTD(1)),
    `aggregate_id` String,
    `version` UInt32 CODEC(Delta, ZSTD(1)),
    `command_id` String CODEC(ZSTD(1)),
    `request_id` String CODEC(ZSTD(1)),
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    `owner_id` String CODEC(ZSTD(1)),
    `space_id` String CODEC(ZSTD(1)),
    `deleted` Bool,
    `access` String CODEC(ZSTD(1)),
    `correlation_id` String CODEC(ZSTD(1)),
    `causation_id` String CODEC(ZSTD(1)),
    `headers` Map(String, String) CODEC(ZSTD(1)),
    `operator_id` String CODEC(ZSTD(1)),
    `operator_tenant_id` String CODEC(ZSTD(1)),
    `operator_acted_elsewhere` Bool,
    `events` Array(Tuple(name LowCardinality(String), revision UInt32, body String)) CODEC(ZSTD(1)),
    `state` String CODEC(ZSTD(1)),
    `state_fingerprint` String CODEC(ZSTD(1)),
    `state_numbers` Map(String, String) CODEC(ZSTD(1))
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version)
SETTINGS non_replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state`
(
    `id` String CODEC(ZSTD(1)),
    `tenant_id` String CODEC(ZSTD(1)),
    `aggregate_id` String,
    `version` UInt32 CODEC(Delta, ZSTD(1)),
    `command_id` String CODEC(ZSTD(1)),
    `request_id` String CODEC(ZSTD(1)),
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    `owner_id` String CODEC(ZSTD(1)),
    `space_id` String CODEC(ZSTD(1)),
    `deleted` Bool,
    `access` String CODEC(ZSTD(1)),
    `correlation_id` String CODEC(ZSTD(1)),
    `causation_id` String CODEC(ZSTD(1)),
    `operator_id` String CODEC(ZSTD(1)),
    `operator_tenant_id` String CODEC(ZSTD(1)),
    `operator_acted_elsewhere` Bool,
    `state` String CODEC(ZSTD(1)),
    `state_fingerprint` String CODEC(ZSTD(1)),
    `state_numbers` Map(String, String) CODEC(ZSTD(1)),
    `access_owner` Nullable(String) MATERIALIZED JSONExtract(access, 'owner', 'Nullable(String)') COMMENT 'wow:cb2ca92a12a84185',
    `access_space` Nullable(String) MATERIALIZED JSONExtract(access, 'space', 'Nullable(String)') COMMENT 'wow:d04ee76ae254029d',
    `status` String MATERIALIZED JSONExtractString(state, 'status') COMMENT 'wow:7770d215250c3cc1',
    `note` Nullable(String) MATERIALIZED JSONExtract(state, 'note', 'Nullable(String)') COMMENT 'wow:8e528fd6dd433f30',
    `quantity` Int64 MATERIALIZED JSONExtract(state, 'quantity', 'Int64') COMMENT 'wow:de224aeb8bf7629c',
    `total` Decimal(38, 2) MATERIALIZED toDecimal128OrZero(state_numbers['total'], 2) COMMENT 'wow:336ba53b0b5c800e',
    `price` Decimal(38, 0) MATERIALIZED toDecimal128OrZero(state_numbers['price'], 0) COMMENT 'wow:f273feebcfeba26b',
    `discount` Nullable(Decimal(38, 18)) MATERIALIZED toDecimal128OrNull(state_numbers['discount'], 18) COMMENT 'wow:f41862a517046c64',
    `rate` Float64 MATERIALIZED JSONExtract(state, 'rate', 'Float64') COMMENT 'wow:7bd9491e8e41a742',
    `paid` Bool MATERIALIZED JSONExtract(state, 'paid', 'Bool') COMMENT 'wow:9239a7d46de01f6e',
    `state_version` Int64 MATERIALIZED JSONExtract(state, 'version', 'Int64') COMMENT 'wow:4e0c748d3474d842'
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS non_replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events`
(
    `id` String CODEC(ZSTD(1)),
    `tenant_id` String CODEC(ZSTD(1)),
    `aggregate_id` String,
    `version` UInt32 CODEC(Delta, ZSTD(1)),
    `sequence` UInt32,
    `event_name` LowCardinality(String),
    `event_revision` UInt32,
    `body` String CODEC(ZSTD(1)),
    `command_id` String CODEC(ZSTD(1)),
    `request_id` String CODEC(ZSTD(1)),
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    `owner_id` String CODEC(ZSTD(1)),
    `space_id` String CODEC(ZSTD(1)),
    `correlation_id` String CODEC(ZSTD(1)),
    `causation_id` String CODEC(ZSTD(1))
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version, sequence)
SETTINGS non_replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_state_mv` TO `default`.`shop_order_state` AS
(SELECT id, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, owner_id, space_id, deleted, access, correlation_id, causation_id, operator_id, operator_tenant_id, operator_acted_elsewhere, state, state_fingerprint, state_numbers
FROM `default`.`shop_order_state_events`)
COMMENT 'wow-sink-format:1';

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_events_mv` TO `default`.`shop_order_events` AS
(SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, event.revision AS event_revision, event.body AS body, command_id, request_id, command_type, commit_time, owner_id, space_id, correlation_id, causation_id
FROM `default`.`shop_order_state_events`
ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence)
COMMENT 'wow-sink-format:1';

CREATE OR REPLACE VIEW `default`.`shop_order_state_flat` AS
SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, `access`, `status`, `note`, `quantity`, `total`, `price`, `discount`, `rate`, `paid`, `state_version`
FROM `default`.`shop_order_state` FINAL
COMMENT 'wow-sink-format:1 wow-sink-flat:abec05d592597f99';
```

排序键就是一行的身份：键与先前某行相同的行，在 ClickHouse 合并时替换它。

| 表 | 引擎 | 分区 | 排序键 |
| --- | --- | --- | --- |
| `_state_events` | `ReplacingMergeTree` | `commit_time` 所在的月 | `(tenant_id, aggregate_id, version)` |
| `_state` | `ReplacingMergeTree(version)`：同一个键保留版本最高的行 | 无 | `(tenant_id, aggregate_id)` |
| `_events` | `ReplacingMergeTree` | `commit_time` 所在的月 | `(tenant_id, aggregate_id, version, sequence)` |
| `wow_catalog` | `ReplacingMergeTree(written_at)` | 无 | `(database, table, fingerprint)` |

导入的历史保留它原来的提交时间，所以它的提交落在各自发生的月份里，Sink 的一次插入可能跨越其中许多个月：它的插入设置 `max_partitions_per_insert_block = 0`，所以一批数据永远不会因为涉及的月份数量而被拒绝。

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
| `operator_id`、`operator_tenant_id`、`operator_acted_elsewhere` | 谁提交的；没有时为空和 false |
| `events` | 这次提交的事件，每个是 `(name, revision, body)`，body 即事件的数据，为 JSON 文本；仅 `_state_events` 有 |
| `state`、`state_fingerprint` | 提交之后的状态（JSON 文本）和它的 schema 的指纹；不发布状态的聚合为空 |
| `state_numbers` | 状态每个顶层数字的原文，`_state_flat` 从中读取小数 |

**`_events`**，每个事件一行：`sequence`（事件在它所属提交中的位置，从 1 开始）、`event_name`、`event_revision` 和 `body`（事件的数据），以及这次提交的 `id`、`tenant_id`、`aggregate_id`、`version`、`command_id`、`request_id`、`command_type`、`commit_time`、`owner_id`、`space_id`、`correlation_id` 和 `causation_id`。

**`_state` 的计算列。** 在存储列之后，`_state` 还有 ClickHouse 在插入一行时由它们计算的列（`MATERIALIZED`）：任何插入都不设置它们，`SELECT *` 也不包含它们，所以要按名字读。每一列的注释记着它的类型与表达式的指纹（`wow:<指纹>`）。它们是：

- **每个访问属性一列**，`access_<name>`，从 `access` 读取：先是内置的 `owner` 与 `space`，`Nullable(String)`，再是聚合声明的每一个，按属性的类型是 `Nullable(String)`、`Nullable(Int64)` 或 `Nullable(Bool)`，多值属性是 `Array(String)`、`Array(Int64)` 或 `Array(Bool)`。没有该属性的行是 `NULL` 或空数组。声明的属性不会叫 `owner` 或 `space`，所以不会有两列同名。绑定到该聚合的数据集按这些列读取其访问策略的限制（[数据集](../guide/datasets)）。
- **状态 schema 的每个顶层标量属性一列展平列**，顺序与 schema 相同，从 `state` 读取（小数从 `state_numbers` 读取）。

**`_state_flat`** 是 `_state FINAL` 的视图：`id`、`tenant_id`、`aggregate_id`、`version`、`commit_time`、`owner_id`、`space_id`、`deleted` 和 `access`，随后是展平列：

| 属性 | 它的列 |
| --- | --- |
| `string`、枚举 | `String` |
| `integer` | `Int64` |
| 带 `x-wow-decimal` 或 `x-wow-money` 的 `number` | `Decimal(38, scale)`：scale 取关键字给出的值，否则取币种的默认小数位数，再否则为 18 |
| 其他 `number` | `Float64` |
| `boolean` | `Bool` |

允许 `null` 的属性是 `Nullable`。对象、数组、引用和组合不生成列；用 ClickHouse 的 JSON 函数从 `state` 读取。与 `_state` 已有的列（存储列或访问列）重名的属性加前缀 `state_`，加后仍重名就再加，示例中的 `version` 就是如此。

## 投递一次，读取一次 {#delivered-once-read-once}

Sink 是一个订阅，所以一次提交可能不止一次到达它：重试、重放、交接。有两个机制让数仓保持精确：

- 每张表记住它最近的 `wow.clickhouse.sink.deduplication-window`（1,000）个插入块，所以重试的插入会被丢弃，视图的表也一样。
- 超出这个窗口时，历史表是以聚合、版本和事件为键的 `ReplacingMergeTree`：重放的行会在合并中去掉，用 `FINAL` 读取时每次提交、每个事件都只计一次。

## Sink 多久插入一次 {#how-often-the-sink-inserts}

每次插入都会在它到达的每张表中、在它的提交涉及的每个月份里生成一个 part：基表 `_state_events`，以及两个视图写入的 `_state` 与 `_events`（不保留历史时只有 `_state` 一个 part）。ClickHouse 随后合并这些 part，所以它的开销取决于接收多少次插入，而不是这些插入携带多少行。

- **每个节点上每个聚合类型一次只有一个插入**，携带所有聚合一同等待的提交，每个聚合的按版本顺序，最多 `wow.clickhouse.sink.batch-size`（1,000）条。
- **负载下每个 `wow.clickhouse.sink.insert-interval`（`PT1S`）最多插入一次**：一次插入不早于上一次开始之后这么久开始，除非已有满批在等待；其间到达的提交并入这次插入。一个提交到达 ClickHouse 的时间，最多比不限速时晚一个间隔。空闲的 Sink 立即插入；积压的提交以满批写入，按全速清空。
- **因此负载下的上限**是节点数 × Sink 写入的聚合类型数 ÷ 间隔：三个节点写一个聚合类型，每秒最多插入三次，每秒九个 part。

不设间隔（`0`）时，Sink 在每次插入返回后立即开始下一次，每次只携带上一次插入期间到达的提交：在发布演练中，这是每分钟数千次、每次几行的插入，内存限制为 2 GiB 的 ClickHouse 在负载下拒绝了它们（[#6494](https://github.com/Ahoo-Wang/Wow/issues/6494)）。限速后的 Sink 需要多少内存，在演练测得之前，这里不给数字。

一次插入是订阅处理器超时之内的一次尝试，而它开始之前可能要等待最多一个间隔：间隔超过该超时的一半时，节点拒绝启动这个 Sink，并指出两个设置（`wow.subscription.retry.timeout`，或 `wow.subscriptions.[<context>.<name>].retry.timeout`）。批大小也要让一次满批的插入能在超时之内完成。

Sink 不使用 ClickHouse 的 `async_insert`，也不需要它：Sink 自己把插入成批。若为 Sink 的用户开启它，必须保持 `wait_for_async_insert = 1`，让插入在行进入 part 之后才得到应答；若在此之前应答，Sink 会把检查点移过那些在刷写失败时丢失的行。异步插入下，按令牌对重试的插入去重只在复制表上成立（`async_insert_deduplicate`）；除此之外，由 `ReplacingMergeTree` 的键把重放的行合并掉。

## schema 管理 {#schema-management}

`wow.clickhouse.ddl` 决定 Sink 在第一次写入之前以及之后不时怎样对待它的表：`auto`（默认）创建缺失的部分并校验其余部分；`validate` 只检查；`none` 两者都不做。被删除或被修改过的表会让写入失败，订阅会重试，直到它恢复成 Sink 期望的样子。

Sink 保有的每张表和每个视图都在注释里写明 Sink 表的格式：`wow-sink-format:1`。Sink 期望的表或视图存在、至少有上面的列且各列类型相同（顺序不限）、并写明这个格式，才算 Sink 的：其他列，无论是运维自己加的（普通、`MATERIALIZED` 或 `ALIAS`）还是更新版本在同一格式内加的，都不构成理由。否则 Sink 拒绝写入，逐一指出是哪一个、哪里不对（`<table> is not of format 1`、`<table> has no <column> <type>`、`<table> has <column> <type>, not <type>`）。`auto` 用这个注释创建缺失的部分；对已存在的表，只添加 Wow 在同一格式内新增的列（带默认值：`ALTER TABLE <db>.<table> ADD COLUMN IF NOT EXISTS <column> <type> DEFAULT <default>`；10.0 没有这样的列）以及下面的计算列，`validate` 会给出这些语句。别的格式的表，或缺少格式中其他列、或列类型不同的表，在任何模式下都会被拒绝，并保持原样。这些表里没有日志之外的东西：删掉它们，Sink 的新一代即可从日志重新填充（[重建数仓](#rebuild-the-warehouse)）。`none` 什么也不检查。

10.0 预发布版写的表随升级重建：它们写明格式 1，但事件的数据在那里叫 `payload`，而现在叫 `body`，Sink 会拒绝它们并指出列（`<ctx>_<agg>_events has no body String`，以及历史表 `events` 的类型不同）。删掉它们并提高 Sink 的代数（[重建数仓](#rebuild-the-warehouse)）。在什么都不检查的 `ddl: none` 下，Sink 会照原样写入它们：ClickHouse 丢弃每个事件的 `body`（旧的元组没有这个元素），`payload` 留空。

Sink 既不检查也不更改列的编解码器，所以早先版本建的表保留建表时的编解码器。要让它用上面的编解码器，对每一列执行 `ALTER TABLE <db>.<table> MODIFY COLUMN <column> <type> CODEC(<codec>)`，此后写入与合并的部分会用它；或者删掉 Sink 的表并调高它的代数，让它重新填满（[重建数仓](#rebuild-the-warehouse)）。

滚动升级期间，一个服务的两个版本可能同时写入两种状态 schema。展平视图在注释里、格式之后，记住创建它所用的定义的指纹（`wow-sink-format:1 wow-sink-flat:<指纹>,…`），写入者只会用更新的定义替换它，所以重启的旧节点不会撤销新节点的视图。

### 状态 schema 变化时 {#when-the-state-schema-changes}

`_state` 至少有写入者期望的计算列、且每一列的指纹相同，才算 Sink 的。写入者不期望的计算列（别的写入者的）保持原样。

- **新增属性或访问属性。** `auto` 在展平视图选取它之前加上它的列：`ALTER TABLE <db>.<ctx>_<agg>_state ADD COLUMN IF NOT EXISTS <列> <类型> MATERIALIZED <表达式> COMMENT 'wow:<指纹>' AFTER <它前面的列>`。这只改元数据：之前写入的 part 在读取时计算，直到合并重写它，而 `_state` 随写入不断合并。要立即在每个 part 里算出来，可执行 `ALTER TABLE <db>.<ctx>_<agg>_state MATERIALIZE COLUMN <列>`，这是一次 mutation，可选。在 `validate` 下，Sink 拒绝写入，并给出添加这些列的语句。旧的写入者遇到自己不计算的列，照常写入。
- **属性的类型变了**（字段变为可空、小数的标度变了、字符串变成数字）。这一列的定义不同，ClickHouse 不重写每一行就无法修改它：Sink 拒绝写入，指出这一列、它的类型以及这个版本计算的类型。类型变化不能滚动进行：旧的写入者会一直写到被替换；之后删除这些表并调高 Sink 的代数（[重建数仓](#rebuild-the-warehouse)）。
- **删除属性。** 它的列保留，每次插入仍然计算，直到你删除它：没有写入者再计算它之后，执行 `ALTER TABLE <db>.<ctx>_<agg>_state DROP COLUMN <列>`。
- **后来声明的访问属性** 立即有它的列，但每一行保留写入时的属性：聚合最新的一行在它下一次提交之前没有这个属性的值。

在集群上，一列要加到本地表和分布式表上，这两条语句 Sink 自己无法执行：

```sql
ALTER TABLE `default`.`shop_order_state_local` ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `weight` Float64 MATERIALIZED JSONExtract(state, 'weight', 'Float64') COMMENT 'wow:<fingerprint>' AFTER `state_version`;
ALTER TABLE `default`.`shop_order_state` ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `weight` Float64 MATERIALIZED JSONExtract(state, 'weight', 'Float64') COMMENT 'wow:<fingerprint>' AFTER `state_version`;
```

`validate` 打印的单机语句带有这一列的指纹，这里用的就是它。

## 在集群上运行 {#run-it-on-a-cluster}

Wow 9.x 默认用 `ON CLUSTER '{cluster}'` 建表：复制的 `_local` 表，前面是一张按租户和聚合 id 分片的分布式表。Wow 10 的 Sink 只建单机表。上面的每条语句只在 Sink 的端点所连的那个节点上运行，没有任何设置能让 Sink 写 `ON CLUSTER`、复制引擎或分布式表。生成集群的 DDL 是 10.x 的候选，不在 10.0 之内。在集群上，请自己用上面的列、视图和键建表，并让 Sink 检查它们。

Sink 按上面的名字插入和读取它的表，所以这些名字是分布式表，每个分片上它们背后是复制的 `_local` 表。视图在每个节点上运行，读取并写入自己分片的本地表。下面的语句是 Sink 的语句，改写成适合定义了宏 `{cluster}`、`{shard}` 和 `{replica}` 的集群，仍是同一个 `shop` 订单：

<!-- sample: backends/clickhouse/src/test/resources/sink/shop_order.cluster.sql -->

```sql
-- Run on any node of the cluster; {cluster}, {shard}, {replica} and {database} are the macros ClickHouse defines for it.
-- The Sink inserts into and reads the Distributed tables, which carry the names it expects; a _local table is one shard's replicated copy.

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_events_local` ON CLUSTER '{cluster}'
(
    `id` String CODEC(ZSTD(1)),
    `tenant_id` String CODEC(ZSTD(1)),
    `aggregate_id` String,
    `version` UInt32 CODEC(Delta, ZSTD(1)),
    `command_id` String CODEC(ZSTD(1)),
    `request_id` String CODEC(ZSTD(1)),
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    `owner_id` String CODEC(ZSTD(1)),
    `space_id` String CODEC(ZSTD(1)),
    `deleted` Bool,
    `access` String CODEC(ZSTD(1)),
    `correlation_id` String CODEC(ZSTD(1)),
    `causation_id` String CODEC(ZSTD(1)),
    `headers` Map(String, String) CODEC(ZSTD(1)),
    `operator_id` String CODEC(ZSTD(1)),
    `operator_tenant_id` String CODEC(ZSTD(1)),
    `operator_acted_elsewhere` Bool,
    `events` Array(Tuple(name LowCardinality(String), revision UInt32, body String)) CODEC(ZSTD(1)),
    `state` String CODEC(ZSTD(1)),
    `state_fingerprint` String CODEC(ZSTD(1)),
    `state_numbers` Map(String, String) CODEC(ZSTD(1))
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}')
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version)
SETTINGS replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

-- _state has the columns of _state_events but the events and the headers, and computes the flat ones.
CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_local` ON CLUSTER '{cluster}'
(
    `id` String CODEC(ZSTD(1)),
    `tenant_id` String CODEC(ZSTD(1)),
    `aggregate_id` String,
    `version` UInt32 CODEC(Delta, ZSTD(1)),
    `command_id` String CODEC(ZSTD(1)),
    `request_id` String CODEC(ZSTD(1)),
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    `owner_id` String CODEC(ZSTD(1)),
    `space_id` String CODEC(ZSTD(1)),
    `deleted` Bool,
    `access` String CODEC(ZSTD(1)),
    `correlation_id` String CODEC(ZSTD(1)),
    `causation_id` String CODEC(ZSTD(1)),
    `operator_id` String CODEC(ZSTD(1)),
    `operator_tenant_id` String CODEC(ZSTD(1)),
    `operator_acted_elsewhere` Bool,
    `state` String CODEC(ZSTD(1)),
    `state_fingerprint` String CODEC(ZSTD(1)),
    `state_numbers` Map(String, String) CODEC(ZSTD(1)),
    `access_owner` Nullable(String) MATERIALIZED JSONExtract(access, 'owner', 'Nullable(String)') COMMENT 'wow:cb2ca92a12a84185',
    `access_space` Nullable(String) MATERIALIZED JSONExtract(access, 'space', 'Nullable(String)') COMMENT 'wow:d04ee76ae254029d',
    `status` String MATERIALIZED JSONExtractString(state, 'status') COMMENT 'wow:7770d215250c3cc1',
    `note` Nullable(String) MATERIALIZED JSONExtract(state, 'note', 'Nullable(String)') COMMENT 'wow:8e528fd6dd433f30',
    `quantity` Int64 MATERIALIZED JSONExtract(state, 'quantity', 'Int64') COMMENT 'wow:de224aeb8bf7629c',
    `total` Decimal(38, 2) MATERIALIZED toDecimal128OrZero(state_numbers['total'], 2) COMMENT 'wow:336ba53b0b5c800e',
    `price` Decimal(38, 0) MATERIALIZED toDecimal128OrZero(state_numbers['price'], 0) COMMENT 'wow:f273feebcfeba26b',
    `discount` Nullable(Decimal(38, 18)) MATERIALIZED toDecimal128OrNull(state_numbers['discount'], 18) COMMENT 'wow:f41862a517046c64',
    `rate` Float64 MATERIALIZED JSONExtract(state, 'rate', 'Float64') COMMENT 'wow:7bd9491e8e41a742',
    `paid` Bool MATERIALIZED JSONExtract(state, 'paid', 'Bool') COMMENT 'wow:9239a7d46de01f6e',
    `state_version` Int64 MATERIALIZED JSONExtract(state, 'version', 'Int64') COMMENT 'wow:4e0c748d3474d842'
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}', version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events_local` ON CLUSTER '{cluster}'
(
    `id` String CODEC(ZSTD(1)),
    `tenant_id` String CODEC(ZSTD(1)),
    `aggregate_id` String,
    `version` UInt32 CODEC(Delta, ZSTD(1)),
    `sequence` UInt32,
    `event_name` LowCardinality(String),
    `event_revision` UInt32,
    `body` String CODEC(ZSTD(1)),
    `command_id` String CODEC(ZSTD(1)),
    `request_id` String CODEC(ZSTD(1)),
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    `owner_id` String CODEC(ZSTD(1)),
    `space_id` String CODEC(ZSTD(1)),
    `correlation_id` String CODEC(ZSTD(1)),
    `causation_id` String CODEC(ZSTD(1))
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
(SELECT id, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, owner_id, space_id, deleted, access, correlation_id, causation_id, operator_id, operator_tenant_id, operator_acted_elsewhere, state, state_fingerprint, state_numbers
FROM `default`.`shop_order_state_events_local`)
COMMENT 'wow-sink-format:1';

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_events_mv` ON CLUSTER '{cluster}' TO `default`.`shop_order_events_local` AS
(SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, event.revision AS event_revision, event.body AS body, command_id, request_id, command_type, commit_time, owner_id, space_id, correlation_id, causation_id
FROM `default`.`shop_order_state_events_local`
ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence)
COMMENT 'wow-sink-format:1';

CREATE OR REPLACE VIEW `default`.`shop_order_state_flat` ON CLUSTER '{cluster}' AS
SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, `access`, `status`, `note`, `quantity`, `total`, `price`, `discount`, `rate`, `paid`, `state_version`
FROM `default`.`shop_order_state` FINAL
COMMENT 'wow-sink-format:1 wow-sink-flat:abec05d592597f99';
```

- **设置 `wow.clickhouse.ddl: validate`。** 此时 Sink 不创建任何东西，期望的表或视图缺失、缺少格式中的列或列类型不同、或没有写明 Sink 的格式时拒绝写入，并指出是哪一个。分布式表能通过，因为 ClickHouse 报告它们的列与本地表相同。Sink 从分布式表自己的注释读取格式，`AS` 不会复制注释，所以示例给每一张都写了 `COMMENT`。`auto` 会在端点所连的那一个节点上把缺失的表建成单机表；`none` 什么也不检查。
- **按聚合分片。** 分片键是 `sipHash64(tenant_id, aggregate_id)`，与 9.x 相同，所以一个聚合的所有提交都在同一个分片上：`_state` 保留同一个键版本最高的行，历史表按各自的键合并掉重放的行，两者都只在一个分片之内生效。
- **每个分片一个副本接收写入。** 在集群的分片上设置 `<internal_replication>true</internal_replication>`，与任何复制表一样：分布式插入于是只写分片的一个副本，由副本互相复制。
- **同步插入。** 默认情况下，ClickHouse 对分布式表的插入在行落到接收节点的磁盘后就应答，之后才把它们发往各分片。Sink 把应答当作提交已在数仓中，并推进它的检查点。让 Sink 的用户同步插入，这样应答就表示各分片已收到这些行：

  ```sql
  CREATE SETTINGS PROFILE IF NOT EXISTS wow_sink ON CLUSTER '{cluster}' SETTINGS insert_distributed_sync = 1 TO wow;
  ```

  `wow` 是 `wow.clickhouse.username` 中的用户。

- **去重。** `non_replicated_deduplication_window` 对复制表不适用，对应的设置是 `replicated_deduplication_window`；示例把它设为 Sink 的默认值。`wow.clickhouse.sink.deduplication-window` 只设置 Sink 自己创建的表。
- **展平视图** 就是 Sink 的语句加上 `ON CLUSTER`。它的列，以及 `_state` 的计算列，来自聚合的状态 schema 与访问属性：要得到另一个聚合的语句，让 Sink 先对一个单机 ClickHouse 创建一次，再读取 `SHOW CREATE TABLE <ctx>_<agg>_state` 与 `SHOW CREATE VIEW <ctx>_<agg>_state_flat`。分布式的 `_state` 通过 `AS` 从 `_state_local` 取得计算列，并按名字从分片读取。
- **`wow_catalog`** 不在示例中。分析目录读取它，Sink 通过端点所连的节点写入它。没有它时，Sink 记录写入失败并继续；要使用分析目录，就按上面的语句，在 Sink 和分析目录都能访问到的地方创建它。

## 命令不写入 {#commands-are-not-written}

Wow 9.x 的 BI 同步还会创建一张命令表 `<ctx>_<agg>_command`，由命令主题填充。Wow 10 不往 ClickHouse 写命令：Sink 复制的是提交，所以被拒绝的命令、或没有产生变化的命令不会留下任何一行。提交对它的命令保留的内容在 `_state_events` 中：`command_id`、`request_id` 和 `command_type`；操作者 `operator_id`、`operator_tenant_id` 和 `operator_acted_elsewhere`；`correlation_id` 和 `causation_id`（Saga 的命令对应它所处理的那次提交）；`headers` 中的请求事实；以及这个命令产生的事件和它们的 body。命令的正文不保存。要得到命令的历史：

- **从提交中读取**，看哪个命令在什么时候、为谁改变了一个聚合：

  ```sql
  SELECT version, command_type, commit_time, operator_id, request_id
  FROM shop_order_state_events FINAL
  WHERE tenant_id = 't1' AND aggregate_id = 'order-1'
  ORDER BY version
  ```

- **把审计需要的命令信息放进事件。** 命令的业务数据通常会进入事件的 body，`_events.body` 按事件保存它。
- **拒绝在发生之处读取**，而不是在数仓中：`wow.command.rejections` 统计它们，拒绝日志列出它们（[健康、指标与追踪](./observability)）。

## wow_catalog {#wow-catalog}

Sink 为它保存的每张表，在数据库的 `wow_catalog` 中追加一行，内容来自 Manifest 而不是表名：表及其角色（`state_events`、`state`、`events`、`state_flat`），它所属的服务、上下文和聚合，聚合的归属以及租户、所有者和空间所在的列，按路径列出的敏感值和引用，它的事件的修订号，以及该聚合的访问策略是否决定它的行（`access`：按服务的访问策略为 `POLICY` 或 `DEFAULT`）。分析工具和分析目录读取它，以了解一张表保存什么、怎样限定范围和脱敏；分析目录的数据集不读取聚合有策略的表。写入它失败只记日志，从不让 Sink 失败。

## 数据集 {#datasets}

服务在它的表之上声明数据集（`META-INF/wow/dataset/<context>.<dataset>.json`），节点在 ClickHouse 中查询它们：例如 ordering 基于它的订单的销售数据集。`wow.clickhouse.query.enabled` 提供它们，默认打开；`wow.clickhouse.query.settings.<name>` 设置每个数据集查询运行时的 ClickHouse 设置，例如 `max_memory_usage`。有三类设置会被拒绝，节点不会启动，并指出是哪个设置：执行时间，它来自每个查询的预算；决定行如何写出的设置（`output_format_*`、`date_time_output_format` 以及 Wow 自己设置的其他设置），因为 Wow 要读取这些行；以及非 `throw` 的溢出模式（例如 `result_overflow_mode=break`），它会把部分结果当作全部结果返回。溢出模式保持 `throw` 的限制则让查询失败。`wow.query.dataset.max-concurrent` 限制同时运行的数量，这样分析查询就不会占满 Sink 正在写入的数仓。

ClickHouse 无法连接或不能写入时（连接被拒绝或超时、存活副本过少），数据集查询以 503 和 `Retry-After` 回答 `wow.storage.unavailable`，Sink 的订阅暂停它的分区直到 ClickHouse 恢复，不会搁置任何聚合。ClickHouse 达到容量时（同时运行的查询过多、没有空闲连接），查询同样这样回答，Sink 则限流它的分区并退避重试，只有用完背压预算（`wow.subscription.backpressure.max-attempts`）才搁置聚合。

## 重建数仓 {#rebuild-the-warehouse}

Sink 是一个有自己检查点的订阅，所以数仓从来不是任何东西唯一的副本：Sink 的新一代从日志开头把它重新填满。

1. 删掉要重新填充的聚合类型的 Sink 表与视图（[表](#the-tables)），或者 Sink 的整个数据库。
2. 把 `wow.clickhouse.sink.generation` 加一，默认 1。
3. 滚动重启节点。

Sink 随后作为新的订阅 `wow.clickhouse-sink-g<n>.<aggregate>` 运行，它写的每个流一个，各自从日志开头写起；在 `ddl: auto` 下，它在第一次插入之前重新建表。代数只能调高：调低会续用那一代的检查点，已删除的不会再写。节点滚动期间新旧两代都在写，表会像吸收重放一样吸收它：它们的 `ReplacingMergeTree` 引擎以提交的 `ref@version` 为键，每个提交只留一份。不删表就调高代数，新一代会把每个提交再写一遍，表会把第二份合并掉：带 `FINAL` 的读取（扁平视图就是这样读的）立刻只数到每个提交和事件一次，不带 `FINAL` 读表则可能在 ClickHouse 合并分片之前数到两次。旧一代的检查点留着，它停放的条目也留着：每个节点首次启动时以旧名字告警它们，`GET /wow/ops/subscriptions` 把它们列为 `orphans`（[改名与回卷](./ops-console#rename-and-rewind)）。这些停放条目属于已清空的目标：在 ops 控制台里跳过它们。调低代数，较高的那一代也会这样显示为遗留，这正是代数只能调高的原因。
