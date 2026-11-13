---
title: PostgreSQL
description: "Run a service on PostgreSQL: the DataSource, the tables Wow creates, the server settings it needs, failover and synchronous replication, connection pools for appends and queries, placing aggregates on several databases, and schema management."
outline: deep
---

# PostgreSQL

On PostgreSQL, one database holds everything a service stores: its log, its snapshots, its timers, and its checkpoints, leases, failure ledger and reservations. The log appends with one autocommitted statement per commit; under load the node gathers a partition's appends into one statement. This page connects a service, describes the tables, and sizes the pools.

## Connect a service

Put `wow-postgres` and HikariCP on the class path and set `spring.datasource.url`, with `spring.datasource.username` and `spring.datasource.password`: the host makes a pooled `DataSource` of them and chooses PostgreSQL. HikariCP's own settings go under `spring.datasource.hikari.*`, such as `maximum-pool-size`. The pool opens at the first connection. Without the URL the host connects to no database.

An application that defines a `DataSource` bean of its own keeps it, and one with Spring Boot's JDBC module (`spring-boot-starter-jdbc`) gets the one Spring Boot makes of the same properties. The eShop's services set only the properties, in their `postgres` profile:

<!-- sample: examples/eshop/ordering/ordering-server/src/main/resources/application.yaml#postgres -->

```yaml
spring:
  config:
    activate:
      on-profile: postgres
  datasource:
    url: jdbc:postgresql://localhost:5432/eshop_ordering
    username: postgres
    password: postgres
```

`wow.backend: postgres` names the backend instead of leaving it to the beans the application has. Each service has a database of its own; two services never share one.

## The tables

The stores create their tables on first use. Names derive from the context and the aggregate type, so a person or a query service can read one aggregate's data directly:

| Table | Holds |
| --- | --- |
| `wow_log__<context>__<aggregate>` | The log of one aggregate type: one row per committed stream, its facts in columns (version, command, request, scope, causation, operator, access attributes as `jsonb`, `null` when not stamped) and its events |
| `wow_log__<context>__partition` | One counter row per partition of the context's log |
| `wow_log_aggregate` | Which aggregate tables each context's log has |
| `wow_snapshot__<context>__<aggregate>` | One row per aggregate: its latest snapshot, state and access attributes as `jsonb`, queried in place |
| `wow_snapshot_table` | The state revision each snapshot table holds |
| `wow_timer__<context>__<aggregate>` | The scheduled commands of one aggregate type, one row per timer, each in its owner's partition |
| `wow_timer_aggregate` | Which timer tables each context has; the due read unions them in one statement |
| `wow_checkpoint`, `wow_lease`, `wow_lease_member` | Where each subscription stands, and which node owns which partition |
| `wow_failure_ledger` | The parked aggregates |
| `wow_reservation__<context>__<aggregate>` | The unique keys aggregates of one type reserved: one row per (tenant, namespace, key), with its owner |
| `wow_format` | The format version of each structure |

A log table's keys are the position (`partition`, `sequence`), the version (`tenant_id`, `aggregate_id`, `version`) and the request (`tenant_id`, `request_id`): the database itself refuses a second commit of the same version or the same request.

**Formats.** Every structure claims its format version in `wow_format` before it is created. A structure recorded under another version, or one that exists without a record, is refused: a build of Wow never reads or writes a layout it does not know.

## Manage the schema

`wow.ddl` decides what the stores do with their tables:

| `wow.ddl` | Does |
| --- | --- |
| `auto`, the default | Creates what is missing |
| `validate` | Creates nothing, and refuses to start on a missing table or one of another format |
| `none` | Creates and checks nothing |

A deployment whose database account may not create tables runs once with an account that may, or applies the schema itself, and then runs with `validate`. `wow.stores.<name>.ddl` sets it for one store.

The query indexes an aggregate declares (`@QueryIndex`) come from one start under `auto`: it builds each with `CREATE INDEX CONCURRENTLY` and records a fingerprint of the planned definition as the index's comment. `validate` checks that comment, so an index of the name built by hand, without it, or one of an earlier definition is refused, and the next start under `auto` rebuilds it. A release that changes how an index is planned rebuilds the declared indexes once.

## Server settings {#server-settings}

**TCP keepalives.** A node that dies without closing its connections leaves their backends open, and an open transaction among them holds its locks. Set the server's keepalives so that it notices within about a minute and ends them:

```sql
ALTER SYSTEM SET tcp_keepalives_idle = 30;
ALTER SYSTEM SET tcp_keepalives_interval = 10;
ALTER SYSTEM SET tcp_keepalives_count = 3;
SELECT pg_reload_conf();
```

The defaults follow the operating system, often two hours of silence before the first probe.

**Wow's bounds inside its transactions.** Each transaction a store opens starts with one batch of `SET LOCAL`, sent with its first statement in the same round trip (a first statement that batches rows sends it on its own first), and derived from the connection's timeout (`wow.postgres.operation-timeout`, 30 seconds by default), with no setting of its own:

| Setting | Value | So that |
| --- | --- | --- |
| `statement_timeout`, `lock_timeout` | The timeout less 1 second, at least 1 second: 29 seconds by default | The server cancels a statement (`57014`) before the client gives up, and the connection survives |
| `idle_in_transaction_session_timeout` | Twice the timeout: 60 seconds by default | The server ends a transaction its node left open, a crashed node's among them |

A query's transaction gives its own budget instead and opens read-only. The appends, one autocommitted statement each, get no server bound. `SET LOCAL` ends with its transaction, so nothing stays on the application's pool.

**The schema step.** The stores read the catalog before they create anything, so a current schema takes no table lock. A statement that must run waits at most 5 seconds for a lock and is tried up to 5 times in all, backing off from half a second; then the start fails, naming the table and the sessions that hold it. Nodes that start together run one table's steps one at a time.

**Snapshot saves and `fillfactor`.** A snapshot save rewrites its aggregate's row. PostgreSQL keeps that update out of the indexes (a HOT update) only when no indexed column changes and the row's page has room for the new version.

- **A declared index on a `state` field** (`@QueryIndex`) indexes `state`, which every save changes, so every save of that aggregate is a full update, at any `fillfactor`.
- **Without one,** a table whose states fit a page several times keeps room on each page for a new version with:

  ```sql
  ALTER TABLE wow_snapshot__<context>__<aggregate> SET (fillfactor = 80);
  ```

  A page then keeps a fifth of its 8 kB, so 80 suits states up to about 1.6 kB.

Measured on 20,000 snapshots of about 1.2 kB with 100,000 saves:

| `fillfactor` | HOT saves | Heap |
| --- | --- | --- |
| 100 (the default) | 75% | 24 MB at load, 46 MB after the saves |
| 80 | 100% | 37% larger at load, unchanged by the saves |

The setting applies to pages written after it; `VACUUM FULL` rewrites the existing ones, under an exclusive lock. Wow sets no `fillfactor` of its own.

## Failover {#failover}

On one PostgreSQL server, a row is visible only once its transaction commits, and the log's appends commit with their WAL flushed (`synchronous_commit` at its default, `on`). The subscriptions read committed rows, so a crash of the server loses nothing a subscription acted on.

A failover to a standby keeps that guarantee only with synchronous replication. PostgreSQL replicates asynchronously by default, and a promoted asynchronous standby lacks the commits it had not yet received. The old primary had acknowledged those commits, and the subscriptions may already have acted on them. Wow cannot see the replication from its connections: this is the deployment's to decide.

**What an asynchronous failover can leave behind.** The new primary holds an earlier, consistent point of the database: its log, checkpoints and counters stop at the same place. What the subscriptions did with the lost commits stays where it went:

- a saga's derived command, committed in another context;
- a message on Kafka, read by other contexts;
- a projection's write to another system, such as a view or an index;
- a snapshot kept in another store.

The log then gives the lost positions and versions to new commits, so one aggregate version can appear twice outside the service, with different events.

**Finding them.** The checkpoints in the database went back with the log, so they show no gap. Compare the log's head with what is kept outside it:

- a subscription whose checkpoints are in another store stands past its partition's head (`GET /wow/ops/subscriptions` shows each partition's position and head, [The Ops Service and Console](./ops-console#without-the-ops-service));
- the service's Kafka topics, and the read models and systems the subscriptions write, hold commits the log no longer has.

Then recover as after a restore to an earlier point: rebuild the read models kept outside, and tell the owners of the contexts that read the service's streams ([Restore a database](./backup#restore-a-database)).

**Closing it.** Make the failover target a synchronous standby. On the primary's `postgresql.conf`:

```ini
synchronous_standby_names = 'ANY 1 (standby_a, standby_b)'
synchronous_commit = on
```

- **`synchronous_standby_names`** lists the standbys, by their `application_name`, that a commit waits for:
  - `FIRST n (…)` waits for the `n` connected ones listed first;
  - `ANY n (…)` waits for any `n` of them.

  It is set only in the configuration file or on the server's command line.
- **`synchronous_commit = on`**, the default, makes a commit wait until those standbys have flushed it to durable storage. `remote_apply` waits too until they have replayed it, so it is visible to queries there, at a larger delay.
  - `remote_write` waits only for the standby's operating system to have the data, which a crash of that operating system loses.
  - `local` and `off` wait for no standby.
- **Set for Wow's account.** `synchronous_commit` can also be set per role, database or transaction, and a `local` or `off` there undoes the guarantee for Wow's commits. Wow itself lowers it only for the covered snapshot saves (below).
- **What the tails read.** While a commit waits for its standbys, PostgreSQL keeps it invisible to other sessions, so a tail reads nothing a synchronous standby lacks. A waiting commit becomes visible on the primary alone only when its wait is cancelled, or when the primary restarts during it: cancel no backend that waits for replication.
- **Promote a synchronous standby.** With `ANY`, promote the one furthest ahead.

**What it costs a commit.** Each commit waits at least one round trip to its synchronous standbys, longer with `remote_apply`, and holds its locks meanwhile. Keep the standbys close to the primary. A commit waits for as long as its standbys are missing, so with one standby named, a stopped standby stops every commit. Name one more than a commit waits for (`ANY 1` of two, or `FIRST 1` of two). The node's calls wait at most `wow.postgres.operation-timeout`, then fail ([Size the pools](#size-the-pools)).

**The covered snapshots.** A context's log, its snapshot subscription's checkpoints and its snapshots are on one store by default, and its snapshot saves then commit with `synchronous_commit = off`: they wait for neither the local flush nor a standby. This leaves no gap. The checkpoint that covers those snapshots commits after them and waits for the standbys, and the WAL reaches a standby in order, so a standby that has the checkpoint has the snapshots. A snapshot it lacks is past its last checkpoint, and the subscription writes it again from the log.

**On a managed service.**

- Amazon RDS's Multi-AZ DB instance replicates synchronously to its standby, and a Multi-AZ DB cluster commits once at least one reader acknowledges. An RDS read replica is asynchronous.
- Amazon Aurora writes the cluster volume, shared by the writer and its replicas, synchronously to six storage nodes across Availability Zones. A global database replicates to its other Regions asynchronously.

A failover to an asynchronous replica is the case above.

## Size the pools

Each node holds a few connections for its appends, its subscriptions, its timers and its leases, and more for its queries. The appends wait whenever the pool is exhausted, so give the queries a pool of their own:

- Define a second `DataSource` bean and name it as the store's `query-data-source`: snapshot and `events` queries then draw from it, and never hold a connection an append waits for. It may point at a read replica.
- Without one, the queries share the appends' pool, and the node bounds them to a quarter of it, at least 2. It lowers the bound while appends slow down or wait for connections, and raises it as they recover, up to half of the pool ([Configuration Keys](../reference/configuration#wow-query-adaptive)).

Count the connections per database: the pool size times the number of nodes of the service, plus one more pool for a node that replaces a crashed one while the crashed node's connections are still open (until the keepalives above end them), plus what migrations and operators use, within PostgreSQL's `max_connections`.

Each connection a store borrows waits at most `wow.postgres.operation-timeout` for a reply (30 seconds by default, a store's `operation-timeout`, `wow.stores.<name>.operation-timeout`, overrides it), and the schema's statements at most 5 minutes: a reply lost on the way, in a failover or behind a proxy, fails the call as a transient error and its connection is replaced, instead of blocking it. A pool whose driver already has a shorter `socketTimeout` keeps it. The server bounds each of Wow's transactions from the same timeout ([Server settings](#server-settings)).

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
    store: orders
  aggregates:
    "[shop.invoice]":
      log:
        store: archive
```

Each store runs on the `DataSource` bean it names, and two log stores may not share one database. An aggregate type placed on another store keeps its log, its subscriptions' checkpoints, its failure ledger, its timers and its reservations there; the leases stay on the default log store.

## Backups

The log is the only fact: snapshots, read models and the warehouse are rebuilt from it. Back up the log tables with the checkpoints, leases, ledger, reservations and timers, as one consistent database backup.
