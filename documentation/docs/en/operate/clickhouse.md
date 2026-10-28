---
title: ClickHouse
description: Write every aggregate's commits to ClickHouse with the warehouse sink, the tables it keeps, deduplication and retention, the wow_catalog, and serving datasets from it.
outline: deep
---

# ClickHouse

ClickHouse is Wow's warehouse. A sink copies each published aggregate's commits into it, from which ClickHouse derives the current state and one row per event, and the service's datasets are queried there. The log stays the only fact: the warehouse can be dropped and filled again from it. This page connects a service and describes the tables.

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

## Delivered once, read once

The sink is a subscription, so a commit may reach it more than once: a retry, a replay, a hand-over. Two mechanisms keep the warehouse exact:

- Every table remembers its last `wow.clickhouse.sink.deduplication-window` (1,000) insert blocks, so a retried insert is dropped, from the views' tables too.
- Beyond that window, the history tables are `ReplacingMergeTree`s keyed by the aggregate, version and event: a replayed row is merged away, and a read with `FINAL` counts every commit and event once.

`wow.clickhouse.sink.batch-size` (1,000) is the most commits one insert carries.

## Schema management

`wow.clickhouse.ddl` decides what the sink does with its tables before its first write, and again from time to time: `auto` (the default) creates what is missing and validates the rest; `validate` only checks; `none` does neither. A table dropped or altered fails the writes, which the subscription retries, until it is as the sink expects.

During a rolling upgrade, two versions of a service may write two state schemas at once. The flat view keeps the fingerprints of the definitions it was created with, and a writer replaces it only with a newer one, so an older node that restarts does not undo a newer one's view.

## wow_catalog

The sink appends one row per table it keeps to the database's `wow_catalog`, from the Manifest rather than from the table's name: the table and its role (`state_events`, `state`, `events`, `state_flat`), the service, context and aggregate it belongs to, the aggregate's ownership and the columns of its tenant, owner and space, its sensitive values and references by path, and its events' revisions. Analytics tools and the analytics catalog read it to know what a table holds and how to scope and mask it. A failed write to it is logged and never fails the sink.

## Datasets

A service declares datasets over its tables (`META-INF/wow/dataset/<context>.<dataset>.json`), which the node queries in ClickHouse: the ordering's sales dataset over its orders. `wow.clickhouse.query.enabled` serves them, on by default; `wow.clickhouse.query.settings.<name>` sets the ClickHouse settings every dataset query runs under, such as `max_memory_usage`. `wow.query.dataset.max-concurrent` bounds how many run at once, so analytics queries cannot take a warehouse the sink is writing to.

While ClickHouse cannot be reached or is overloaded (a refused or timed-out connection, too many queries, too few live replicas), a dataset query answers `wow.storage.unavailable` with 503 and `Retry-After`, and the sink's subscription pauses its partition until ClickHouse is back, without parking an aggregate.

## Rebuild the warehouse

The sink is a subscription with checkpoints of its own, so the warehouse is never the only copy of anything: it can always be filled again from the log, from the beginning.
