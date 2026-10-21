---
title: PostgreSQL
description: "Run a service on PostgreSQL: the DataSource, the tables Wow creates, connection pools for appends and queries, placing aggregates on several databases, and schema management."
outline: deep
---

# PostgreSQL

On PostgreSQL, one database holds everything a service stores: its log, its snapshots, its timers, and its checkpoints, leases, failure ledger and reservations. The log appends straight to the database, one autocommitted statement per commit. This page connects a service, describes the tables, and sizes the pools.

## Connect a service

Put `wow-postgres` on the class path and give the application a `DataSource` bean: the host then chooses PostgreSQL. The eShop's services define a pooled one in their `postgres` profile:

<!-- sample: example/eshop/catalog/catalog-server/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/server/PostgresDataSource.kt -->

```kotlin
package me.ahoo.wow.example.eshop.catalog.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import javax.sql.DataSource

/**
 * The `postgres` profile: a pooled `DataSource` on the database `spring.datasource.*` names. Given one, the host keeps
 * the catalog's log and snapshots on PostgreSQL and serves the snapshot queries there (guide: Deployment).
 */
@Configuration(proxyBeanMethods = false)
@Profile(PostgresDataSource.PROFILE)
class PostgresDataSource {
    @Bean(destroyMethod = "close")
    fun dataSource(environment: Environment): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = environment.getRequiredProperty("spring.datasource.url")
            username = environment.getProperty("spring.datasource.username")
            password = environment.getProperty("spring.datasource.password")
        },
    )

    companion object {
        const val PROFILE: String = "postgres"
    }
}
```

and point it at the service's database there:

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
| `wow_timer__<context>` | The context's scheduled commands |
| `wow_checkpoint`, `wow_lease`, `wow_lease_member` | Where each subscription stands, and which node owns which partition |
| `wow_failure_ledger` | The parked aggregates |
| `wow_reservation` | The reserved unique keys |
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

## Size the pools

Each node holds a few connections for its appends, its subscriptions, its timers and its leases, and more for its queries. The appends wait whenever the pool is exhausted, so give the queries a pool of their own:

- Define a second `DataSource` bean and name it as the store's `query-data-source`: snapshot and `events` queries then draw from it, and never hold a connection an append waits for. It may point at a read replica.
- Without one, the queries share the appends' pool, and the node bounds them to half of it. It lowers the bound further while appends slow down or wait for connections, and raises it again when they recover ([Configuration Keys](../reference/configuration#wow-query-adaptive)).

Count the connections per database: the pool size times the number of nodes of the service, plus what migrations and operators use, within PostgreSQL's `max_connections`.

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

Each store runs on the `DataSource` bean it names, and two log stores may not share one database. An aggregate type placed on another store keeps its log, its subscriptions' checkpoints, its failure ledger and its timers there; the leases and the reservations stay on the default log store.

## Backups

The log is the only fact: snapshots, read models and the warehouse are rebuilt from it. Back up the log tables with the checkpoints, leases, ledger, reservations and timers, as one consistent database backup.
