---
title: PostgreSQL
description: "Run a service on PostgreSQL: the DataSource, the tables Wow creates, connection pools for appends and queries, placing aggregates on several databases, and schema management."
outline: deep
---

# PostgreSQL

On PostgreSQL, one database holds everything a service stores: its log, its snapshots, its timers, and its checkpoints, leases, failure ledger and reservations. The log appends with one autocommitted statement per commit; under load the node gathers a partition's appends into one statement. This page connects a service, describes the tables, and sizes the pools.

## Connect a service

Put `wow-postgres` and HikariCP on the class path and set `spring.datasource.url`, with `spring.datasource.username` and `spring.datasource.password`: the host makes a pooled `DataSource` of them and chooses PostgreSQL. HikariCP's own settings go under `spring.datasource.hikari.*`, such as `maximum-pool-size`. The pool opens at the first connection. Without the URL the host connects to no database.

An application that defines a `DataSource` bean of its own keeps it, and one with Spring Boot's JDBC module (`spring-boot-starter-jdbc`) gets the one Spring Boot makes of the same properties. The eShop's services set only the properties, in their `postgres` profile:

<!-- sample: example/eshop/ordering/ordering-server/src/main/resources/application.yaml#postgres -->

```yaml
spring:
  config:
    activate:
      on-profile: postgres
  datasource:
    url: jdbc:postgresql://localhost:5432/eshop_ordering
    username: postgres
    password: postgres
wow:
  backend: postgres
```

`wow.backend: postgres` names the backend instead of leaving it to the beans the application has. Each service has a database of its own; two services never share one.

## The tables

The stores create their tables on first use. Names derive from the context and the aggregate type, so a person or a query service can read one aggregate's data directly:

| Table | Holds |
| --- | --- |
| `wow_log__<context>__<aggregate>` | The log of one aggregate type: one row per committed stream, its facts in columns (version, command, request, scope, causation, operator, tags as `jsonb`) and its events |
| `wow_log__<context>__partition` | One counter row per partition of the context's log |
| `wow_log_aggregate` | Which aggregate tables each context's log has |
| `wow_snapshot__<context>__<aggregate>` | One row per aggregate: its latest snapshot, state and tags as `jsonb`, queried in place |
| `wow_snapshot_table` | The state revision each snapshot table holds |
| `wow_timer__<context>__<aggregate>` | The scheduled commands of one aggregate type, one row per timer, each in its owner's partition |
| `wow_timer_aggregate` | Which timer tables each context has; the due read unions them in one statement |
| `wow_checkpoint`, `wow_lease`, `wow_lease_member` | Where each subscription stands, and which node owns which partition |
| `wow_failure_ledger` | The parked aggregates |
| `wow_reservation__<context>__<aggregate>` | The unique keys aggregates of one type reserved: one row per (tenant, namespace, key), with its owner |
| `wow_format` | The format version of each structure |

A log table's keys are the position (`partition`, `sequence`), the version (`aggregate_id`, `tenant_id`, `version`) and the request (`tenant_id`, `request_id`): the database itself refuses a second commit of the same version or the same request.

**Formats.** Every structure claims its format version in `wow_format` before it is created. A structure recorded under another version, or one that exists without a record, is refused: a build of Wow never reads or writes a layout it does not know. Columns added within a format are added to existing tables when they are next checked.

## Manage the schema

`wow.ddl` decides what the stores do with their tables:

| `wow.ddl` | Does |
| --- | --- |
| `auto`, the default | Creates what is missing |
| `validate` | Creates nothing, and refuses to start on a missing table or one of another format |
| `none` | Creates and checks nothing |

A deployment whose database account may not create tables runs once with an account that may, or applies the schema itself, and then runs with `validate`. `wow.stores.<name>.ddl` sets it for one store.

With `auto`, a store reads the catalog first and runs a schema statement only for what is missing, so a current schema takes no table lock. Nodes starting together run one table's steps one at a time, under an advisory lock of that table. A statement waits at most 5 seconds for its table's lock and gives it back between attempts, so readers never queue behind it for long; after 5 attempts it fails, naming the table and the sessions that hold it.

## Size the pools

Each node holds a few connections for its appends, its subscriptions, its timers and its leases, and more for its queries. The appends wait whenever the pool is exhausted, so give the queries a pool of their own:

- Define a second `DataSource` bean and name it as the store's `query-data-source`: snapshot and `events` queries then draw from it, and never hold a connection an append waits for. It may point at a read replica.
- Without one, the queries share the appends' pool, and the node bounds them to half of it. It lowers the bound further while appends slow down or wait for connections, and raises it again when they recover ([Configuration Keys](../reference/configuration#wow-query-adaptive)).

Count the connections per database: the pool size times the number of nodes of the service, plus what migrations and operators use, within PostgreSQL's `max_connections`.

Each connection a store borrows waits at most `wow.postgres.operation-timeout` for a reply (30 seconds by default, a store's `options.operation-timeout` overrides it), and the schema's statements at most 5 minutes: a reply lost on the way, in a failover or behind a proxy, fails the call as a transient error and its connection is replaced, instead of blocking it. A pool whose driver already has a shorter `socketTimeout` keeps it. Each transaction a store opens tells the server the same bound with `SET LOCAL`: its statements and lock waits stop a second before the client gives up (`statement_timeout`, `lock_timeout`), and it may sit idle at most twice the timeout (`idle_in_transaction_session_timeout`), so the server ends the open transaction of a node that crashed. A query's transaction is read-only and keeps its own budget (`statement_timeout`).

## Several databases

A service whose aggregate types keep their data in different databases names its store instances and places each type on one:

```yaml
wow:
  stores:
    orders:
      type: postgres
      data-source: ordersDataSource
    archive:
      type: postgres
      data-source: archiveDataSource
  log:
    store: orders
  snapshot:
    stores: [orders]
  aggregates:
    "[shop.invoice]":
      log:
        store: archive
```

Each store runs on the `DataSource` bean it names, and two log stores may not share one database. An aggregate type placed on another store keeps its log, its subscriptions' checkpoints, its failure ledger, its timers and its reservations there; the leases stay on the default log store.

## Backups

The log is the only fact: snapshots, read models and the warehouse are rebuilt from it. Back up the log tables with the checkpoints, leases, ledger, reservations and timers, as one consistent database backup.
