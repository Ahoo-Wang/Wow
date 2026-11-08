---
title: ClickHouse
description: Write every aggregate's commits to ClickHouse with the warehouse sink, the tables and columns it keeps and their DDL, a cluster's layout, deduplication and retention, the wow_catalog, and serving datasets from it.
outline: deep
---

# ClickHouse

ClickHouse is Wow's warehouse. A sink copies each published aggregate's commits into it, from which ClickHouse derives the current state and one row per event, and the service's datasets are queried there. The log stays the only fact: the warehouse can be dropped and filled again from it. This page connects a service, describes the tables and their columns, and says how to run them on a cluster.

## Connect a service

Put `wow-clickhouse` on the class path, set `wow.clickhouse.endpoint`, the server's HTTP endpoint, and switch the sink on with `wow.clickhouse.sink.enabled: true`. Writing every event to a warehouse is a cost a service chooses, so the sink is off until that setting says otherwise; neither an endpoint nor a client switches it on. The eShop's ordering service does both in its `clickhouse` profile:

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

`wow.clickhouse.username` (`default`) and `password` sign in, and `wow.clickhouse.database` (`default`) holds the tables. The sink writes every aggregate the Manifest publishes. The datasets are served whenever there is a client, unless `wow.clickhouse.query.enabled: false`.

The sink and the datasets find their client by one rule: the application's ClickHouse `Client` bean when it has one, which Wow never closes; otherwise one of these settings, which each opens and closes with the node. A sink switched on with neither fails the start, naming both ways. An application that wants one shared client, or uses ClickHouse itself, declares the client as a bean: it binds `wow.clickhouse` with Spring's `Binder`, builds the client with `ClickHouseClients.of`, and closes it with its context. The analytics catalog does so, to read the warehouse; its client starts no sink:

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

## The tables

For each aggregate type, named after its context and aggregate (`eshop_ordering_order`):

| Table | Holds |
| --- | --- |
| `<ctx>_<agg>_state_events` | The base table the sink inserts into: one row per commit, its events and the state after it. Partitioned by the month of the commit, ordered by tenant, aggregate and version |
| `<ctx>_<agg>_state` | The current state of each aggregate, filled by a materialized view from the base table |
| `<ctx>_<agg>_events` | One row per event, filled by a materialized view |
| `<ctx>_<agg>_state_flat` | A view of the current state with each top-level scalar of the state as a typed column, for analytics tools |

`wow.aggregates.[<context>.<aggregate>].clickhouse.history.enabled: false` keeps only the current state: the sink then writes `_state` directly, and there is no `_state_events` or `_events`. `...history.ttl` keeps the history for a time instead of forever.

## The columns and the DDL

With `wow.clickhouse.ddl: auto`, the default, the sink runs these statements before its first write. They are the tables of the `order` aggregate of a `shop` context in the `default` database, with the history kept without a TTL. A test holds this file to the statements the sink runs, so the page cannot differ from the code:

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
SETTINGS non_replicated_deduplication_window = 1000;

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
    `headers` Map(String, String),
    `operator_id` String,
    `operator_tenant_id` String,
    `operator_cross_scope` Bool,
    `events` Array(Tuple(name LowCardinality(String), revision UInt32, payload String)),
    `state` String,
    `state_fingerprint` String,
    `state_numbers` Map(String, String)
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS non_replicated_deduplication_window = 1000;

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
SETTINGS non_replicated_deduplication_window = 1000;

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_state_mv` TO `default`.`shop_order_state` AS
SELECT id, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, owner_id, space_id, deleted, access, correlation_id, causation_id, headers, operator_id, operator_tenant_id, operator_cross_scope, events, state, state_fingerprint, state_numbers
FROM `default`.`shop_order_state_events`;

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_events_mv` TO `default`.`shop_order_events` AS
SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, event.revision AS event_revision, event.payload AS payload, command_id, request_id, command_type, commit_time, owner_id, space_id, correlation_id, causation_id
FROM `default`.`shop_order_state_events`
ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence;

CREATE OR REPLACE VIEW `default`.`shop_order_state_flat` AS
SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, `access`, JSONExtractString(state, 'status') AS `status`, JSONExtract(state, 'note', 'Nullable(String)') AS `note`, JSONExtract(state, 'quantity', 'Int64') AS `quantity`, toDecimal128OrZero(state_numbers['total'], 2) AS `total`, toDecimal128OrZero(state_numbers['price'], 0) AS `price`, toDecimal128OrNull(state_numbers['discount'], 18) AS `discount`, JSONExtract(state, 'rate', 'Float64') AS `rate`, JSONExtract(state, 'paid', 'Bool') AS `paid`, JSONExtract(state, 'version', 'Int64') AS `state_version`
FROM `default`.`shop_order_state` FINAL
COMMENT 'wow-sink-flat:eb326856599fc0e7';
```

The sorting key is a row's identity: a row with the same key as an earlier one replaces it when ClickHouse merges them.

| Table | Engine | Partitioned by | Sorting key |
| --- | --- | --- | --- |
| `_state_events` | `ReplacingMergeTree` | the month of `commit_time` | `(tenant_id, aggregate_id, version)` |
| `_state` | `ReplacingMergeTree(version)`: the highest version of a key stays | none | `(tenant_id, aggregate_id)` |
| `_events` | `ReplacingMergeTree` | the month of `commit_time` | `(tenant_id, aggregate_id, version, sequence)` |
| `wow_catalog` | `ReplacingMergeTree(written_at)` | none | `(database, table, fingerprint)` |

Each table of an aggregate has `SETTINGS non_replicated_deduplication_window = N`, `wow.clickhouse.sink.deduplication-window`. `...history.ttl` adds `TTL toDateTime(commit_time) + toIntervalSecond(<seconds>)` to `_state_events` and `_events`. Without the history there are `_state` and `_state_flat` alone.

**`_state_events` and `_state`**, one row per commit:

| Column | Holds |
| --- | --- |
| `id` | The commit's id, `<aggregate reference>@<version>` |
| `tenant_id`, `aggregate_id` | The aggregate's tenant and id; its context and name are the table's |
| `version` | The aggregate's version after the commit |
| `command_id`, `request_id`, `command_type` | The command that made the commit: its id, its idempotency key and its type |
| `commit_time` | When it was committed, in UTC, to the millisecond |
| `owner_id`, `space_id` | The scope of the aggregate after the commit, empty when none |
| `deleted`, `access` | Whether the aggregate is deleted, and its access attributes at the commit as JSON text, the built-in `owner` and `space` among them: information only, never what authorizes a read |
| `correlation_id`, `causation_id` | The root command's id and the direct cause, empty for a command a caller sent directly |
| `headers` | The request facts the command carried |
| `operator_id`, `operator_tenant_id`, `operator_cross_scope` | Who committed it; empty and false when none |
| `events` | The commit's events, each `(name, revision, payload)`, the payload as JSON text |
| `state`, `state_fingerprint` | The state after the commit as JSON text, and the fingerprint of its schema; empty for an aggregate that publishes no state |
| `state_numbers` | The exact text of each top-level number of the state, which `_state_flat` reads decimals from |

**`_events`**, one row per event: `sequence` (the event's place in its commit, from 1), `event_name`, `event_revision` and `payload`, with the commit's `id`, `tenant_id`, `aggregate_id`, `version`, `command_id`, `request_id`, `command_type`, `commit_time`, `owner_id`, `space_id`, `correlation_id` and `causation_id`.

**`_state_flat`** starts with `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted` and `access`, then has a column for each top-level scalar property of the state schema, in its order:

| The property | Its column |
| --- | --- |
| `string`, an enum | `String` |
| `integer` | `Int64` |
| `number` with `x-wow-decimal` or `x-wow-money` | `Decimal(38, scale)`: the keyword's scale, else the currency's default fraction digits, else 18 |
| any other `number` | `Float64` |
| `boolean` | `Bool` |

A property that admits `null` is `Nullable`. Objects, arrays, references and compositions are not columns; read them from `state` with ClickHouse's JSON functions. A property named as one of the nine system columns takes the prefix `state_`, as the sample's `version` does.

## Delivered once, read once

The sink is a subscription, so a commit may reach it more than once: a retry, a replay, a hand-over. Two mechanisms keep the warehouse exact:

- Every table remembers its last `wow.clickhouse.sink.deduplication-window` (1,000) insert blocks, so a retried insert is dropped, from the views' tables too.
- Beyond that window, the history tables are `ReplacingMergeTree`s keyed by the aggregate, version and event: a replayed row is merged away, and a read with `FINAL` counts every commit and event once.

`wow.clickhouse.sink.batch-size` (1,000) is the most commits one insert carries.

## Schema management

`wow.clickhouse.ddl` decides what the sink does with its tables before its first write, and again from time to time: `auto` (the default) creates what is missing and validates the rest; `validate` only checks; `none` does neither. A table dropped or altered fails the writes, which the subscription retries, until it is as the sink expects.

During a rolling upgrade, two versions of a service may write two state schemas at once. The flat view keeps the fingerprints of the definitions it was created with, and a writer replaces it only with a newer one, so an older node that restarts does not undo a newer one's view.

## Run it on a cluster

Wow 9.x created its tables `ON CLUSTER '{cluster}'` by default: replicated `_local` tables with a Distributed table in front, sharded by the tenant and the aggregate id. The Wow 10 sink creates single-node tables only. Each statement above runs on the one node the sink's endpoint reaches, and no setting makes the sink write `ON CLUSTER`, a replicated engine or a Distributed table. Generating a cluster's DDL is a candidate for a 10.x release, not part of 10.0. On a cluster, create the tables yourself, with the columns, views and keys above, and have the sink check them.

The sink inserts into and reads its tables by the names above, so those names are Distributed tables, with replicated `_local` tables behind them on each shard. The views run on every node, from their own shard's local tables. These statements are the sink's, written for a cluster that defines the macros `{cluster}`, `{shard}` and `{replica}`, for the same `shop` order:

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
SETTINGS replicated_deduplication_window = 1000;

-- _state has the columns of _state_events.
CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_local` ON CLUSTER '{cluster}'
AS `default`.`shop_order_state_events_local`
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}', version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS replicated_deduplication_window = 1000;

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
SETTINGS replicated_deduplication_window = 1000;

-- Every commit of an aggregate goes to one shard: the sharding key is its tenant and id.
CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_events` ON CLUSTER '{cluster}'
AS `default`.`shop_order_state_events_local`
ENGINE = Distributed('{cluster}', `default`, `shop_order_state_events_local`, sipHash64(tenant_id, aggregate_id));

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state` ON CLUSTER '{cluster}'
AS `default`.`shop_order_state_local`
ENGINE = Distributed('{cluster}', `default`, `shop_order_state_local`, sipHash64(tenant_id, aggregate_id));

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events` ON CLUSTER '{cluster}'
AS `default`.`shop_order_events_local`
ENGINE = Distributed('{cluster}', `default`, `shop_order_events_local`, sipHash64(tenant_id, aggregate_id));

-- The views run on every node, from its own shard's local tables.
CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_state_mv` ON CLUSTER '{cluster}' TO `default`.`shop_order_state_local` AS
SELECT id, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, owner_id, space_id, deleted, access, correlation_id, causation_id, headers, operator_id, operator_tenant_id, operator_cross_scope, events, state, state_fingerprint, state_numbers
FROM `default`.`shop_order_state_events_local`;

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_events_mv` ON CLUSTER '{cluster}' TO `default`.`shop_order_events_local` AS
SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, event.revision AS event_revision, event.payload AS payload, command_id, request_id, command_type, commit_time, owner_id, space_id, correlation_id, causation_id
FROM `default`.`shop_order_state_events_local`
ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence;

CREATE OR REPLACE VIEW `default`.`shop_order_state_flat` ON CLUSTER '{cluster}' AS
SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, `access`, JSONExtractString(state, 'status') AS `status`, JSONExtract(state, 'note', 'Nullable(String)') AS `note`, JSONExtract(state, 'quantity', 'Int64') AS `quantity`, toDecimal128OrZero(state_numbers['total'], 2) AS `total`, toDecimal128OrZero(state_numbers['price'], 0) AS `price`, toDecimal128OrNull(state_numbers['discount'], 18) AS `discount`, JSONExtract(state, 'rate', 'Float64') AS `rate`, JSONExtract(state, 'paid', 'Bool') AS `paid`, JSONExtract(state, 'version', 'Int64') AS `state_version`
FROM `default`.`shop_order_state` FINAL
COMMENT 'wow-sink-flat:eb326856599fc0e7';
```

- **Set `wow.clickhouse.ddl: validate`.** The sink then creates nothing, and refuses to write while a table or view it expects is missing or has other columns, naming it. The Distributed tables pass, since ClickHouse reports their columns as their local tables'. `auto` would create what is missing on the one node its endpoint reaches, as a single-node table; `none` checks nothing.
- **Shard by the aggregate.** The sharding key is `sipHash64(tenant_id, aggregate_id)`, as in 9.x, so all of an aggregate's commits are on one shard: `_state` keeps the highest version of a key, and the history tables merge a replayed row away by their keys, each within a shard.
- **Replicate by one replica per shard.** Set `<internal_replication>true</internal_replication>` on the cluster's shards, as for any replicated table: a Distributed insert then writes one replica of a shard, and the replicas copy it.
- **Insert synchronously.** By default ClickHouse answers an insert into a Distributed table once the rows are on the receiving node's disk, and sends them to the shards afterwards. The sink takes an answer as the commit being in the warehouse, and moves its checkpoint on. Make the sink's user insert synchronously, which answers once the shards have the rows:

  ```sql
  CREATE SETTINGS PROFILE IF NOT EXISTS wow_sink ON CLUSTER '{cluster}' SETTINGS insert_distributed_sync = 1 TO wow;
  ```

  `wow` is the user in `wow.clickhouse.username`.

- **Deduplication.** `non_replicated_deduplication_window` does not apply to a replicated table, whose counterpart is `replicated_deduplication_window`; the example sets it to the sink's default. `wow.clickhouse.sink.deduplication-window` sets the tables the sink creates only.
- **The flat view** is the sink's statement with `ON CLUSTER`. Its columns come from the aggregate's state schema: to get the statement for another aggregate, let the sink create it once against a single-node ClickHouse, and read `SHOW CREATE VIEW <ctx>_<agg>_state_flat`.
- **`wow_catalog`** is not in the example. The analytics catalog reads it, and the sink writes it through the node its endpoint reaches. Without it the sink logs the failed write and goes on; to use the catalog, create it from the statement above where the sink and the catalog both reach it.

## Commands are not written

Wow 9.x's BI sync also created a table of commands, `<ctx>_<agg>_command`, filled from the command topic. Wow 10 writes no command to ClickHouse: the sink copies commits, so a command that was rejected, or changed nothing, leaves no row. What a commit keeps of its command is in `_state_events`: `command_id`, `request_id` and `command_type`; the operator in `operator_id`, `operator_tenant_id` and `operator_cross_scope`; `correlation_id` and `causation_id`, which for a saga's command is the commit it handled; the request facts in `headers`; and the events the command produced, with their payloads. The command's body is not kept. For a history of commands:

- **Read it from the commits**, for which command changed an aggregate, when, and for whom:

  ```sql
  SELECT version, command_type, commit_time, operator_id, request_id
  FROM shop_order_state_events FINAL
  WHERE tenant_id = 't1' AND aggregate_id = 'order-1'
  ORDER BY version
  ```

- **Put into the event what an audit needs of the command.** A command's business data reaches an event's payload, which `_events.payload` keeps per event.
- **Read rejections where they happen**, not in the warehouse: `wow.command.rejections` counts them and the rejection log lists them ([Health, Metrics and Tracing](./observability)).

## wow_catalog

The sink appends one row per table it keeps to the database's `wow_catalog`, from the Manifest rather than from the table's name: the table and its role (`state_events`, `state`, `events`, `state_flat`), the service, context and aggregate it belongs to, the aggregate's ownership and the columns of its tenant, owner and space, its sensitive values and references by path, and its events' revisions, and whether an access policy of the aggregate decides its rows (`access`: `POLICY` or `DEFAULT`, as the service's access policies say; empty in a row an earlier version wrote). Analytics tools and the analytics catalog read it to know what a table holds and how to scope and mask it; the catalog's datasets read no table whose aggregate has a policy, or whose row does not say. A failed write to it is logged and never fails the sink. `auto` adds `access` to a `wow_catalog` an earlier version created, and `validate` refuses one without it, its writes failing until it is added ([Upgrade a Deployment](./upgrades#from-a-10-0-0-pre-release)).

## Datasets

A service declares datasets over its tables (`META-INF/wow/dataset/<context>.<dataset>.json`), which the node queries in ClickHouse: the ordering's sales dataset over its orders. `wow.clickhouse.query.enabled` serves them, on by default; `wow.clickhouse.query.settings.<name>` sets the ClickHouse settings every dataset query runs under, such as `max_memory_usage`. `wow.query.dataset.max-concurrent` bounds how many run at once, so analytics queries cannot take a warehouse the sink is writing to.

While ClickHouse cannot be reached or cannot take writes (a refused or timed-out connection, too few live replicas), a dataset query answers `wow.storage.unavailable` with 503 and `Retry-After`, and the sink's subscription pauses its partition until ClickHouse is back, without parking an aggregate. While it is at capacity (too many queries at once, no free connection), queries answer the same, and the sink throttles its partition and retries with backoff, parking an aggregate only once its backpressure budget, `wow.subscription.backpressure.max-attempts`, is spent.

## Rebuild the warehouse

The sink is a subscription with checkpoints of its own, so the warehouse is never the only copy of anything: it can always be filled again from the log, from the beginning.
