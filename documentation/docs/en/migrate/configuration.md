---
title: Configuration Keys
description: Where each group of 9.x `wow.*` configuration keys went in V10, which keys have a counterpart and which were removed with what they configured.
---

# Configuration Keys

V10's keys live under `wow.*` too, but none of the 9.x keys carry over: a 9.x key left in place binds to nothing, and the service starts without it. Map every key the 9.x service sets to its V10 counterpart, or record it as removed with what it configured. The `wow-migrate` Skill does this with the source. [Configuration Keys](../reference/configuration) is the complete list of V10 keys, generated from the code; [Configure a Service](../operate/configuration) explains them by task.

## The service and its node

| 9.x | V10 |
| --- | --- |
| `wow.context-name` | The context is the code's `@BoundedContext`; a service serves the contexts on its class path, narrowed with `wow.contexts.include` and `exclude`. Its name is `wow.service`, `spring.application.name` by default |
| `wow.shutdown-timeout`, `wow.shutdown-quiet-period` | `wow.cluster.drain-timeout`, and Spring Boot's own graceful shutdown ([Kubernetes](../operate/kubernetes)) |
| `wow.dispatch.*` (`workers`, `max-in-flight`, `throughput`, `spin`) | `wow.executor.*`: `max-in-flight` for the node, `lane-capacity` for one aggregate |
| `wow.command.bus.*`, `wow.event.bus.*`, `wow.eventsourcing.state.bus.*`, `local-first` | Removed: there is no bus. Subscriptions read the log; a command runs on the node that owns its aggregate's partition (`wow.cluster.*`) |
| `wow.command.idempotency.*` (the Bloom filter) | Removed: a retry with the same `Wow-Request-Id` is answered from its first commit, by the log itself |
| `wow.event.ack-on-unrecorded-failure` | Removed: a failure parks the aggregate in the failure ledger (`wow.subscription.*`), nothing is acknowledged past it |

## Stores

| 9.x | V10 |
| --- | --- |
| `wow.eventsourcing.store.storage`, `wow.eventsourcing.snapshot.storage` | `wow.backend`: PostgreSQL or MongoDB, chosen by the application's `DataSource` or `MongoClient` by default; `wow.log.store` and `wow.snapshot.store` name stores of `wow.stores` |
| `wow.eventsourcing.storage-routing.*` | The stores `wow.aggregates.[<context>.<aggregate>]` names for an aggregate's log and snapshots, such as `log.store` |
| `wow.eventsourcing.snapshot.strategy`, `version-offset` | `wow.snapshot.*`: snapshots are written by the built-in `snapshot` subscription, in batches (`interval`, `batch`) |
| `wow.mongo.event-stream-database`, `snapshot-database`, `prepare-database` | `wow.mongo.database`, one database for the log and every store |
| `wow.mongo.auto-init-schema` | `wow.ddl` (`auto`, `validate`) for the framework's tables, collections and indexes, and `wow.query.index.ddl` for query indexes |
| `wow.mongo.*-batch.*`, `wow.elasticsearch.*-batch.*` | Removed: the log appends in batches of its own (`wow.log.append-queue.*`) |
| `wow.prepare.*` | Removed: reservations live in the backend, taken in decide |
| `wow.redis.*` | Removed with the Redis backend |
| `wow.elasticsearch.*` (event store) | Removed as an event store; in V10 Elasticsearch is a query backend |

## Kafka

| 9.x | V10 |
| --- | --- |
| `wow.kafka.bootstrap-servers` | `wow.kafka.bootstrap-servers`, now for published streams and commands to other contexts only |
| `wow.kafka.properties`, `producer`, `consumer`, `close-timeout` | `wow.kafka.*` of V10 ([Kafka](../operate/kafka)) |
| `wow.kafka.decode-failure-strategy` | Removed: a record a subscription cannot decode parks it (`wow.subscription.undecodable` counts them) |
| Topic names by convention | `wow.<context>.commands` and `wow.<context>.<aggregate>.state`, created as `wow.kafka.topics.ddl` says |

## HTTP and queries

| 9.x | V10 |
| --- | --- |
| `wow.webflux.*` (`global-error`, `batch`, `query.default-list-size`, `state.*`) | `wow.http.*` for the routes, `wow.query.http.*` for their queries: `default-list-size`, `max-list-size`, `max-page-size`, `timeout` |
| `wow.webflux.command.request.appender.*` | `wow.http.request-facts`: the request headers a command keeps |
| `wow.query.http.*` (`max-list-size`, `max-page-size`, `max-filter-nodes`, `allow-expensive-operators`, …) | `wow.query.http.*`, the same names |
| `wow.query.abac.*`, `wow.query.sensitivity.*` | `wow.query.abac.*`, `wow.query.sensitivity.*` |
| `wow.query.schema.*`, `legacy-declarations`, `require-explicit-entry` | Removed: the query model comes from the Manifest |
| `wow.query.datasets`, `wow.query.clickhouse.*` | `wow.query.dataset.*` and `wow.clickhouse.*` ([Publish Datasets](../guide/datasets)) |
| `wow.openapi.enabled` | `wow.http.openapi.enabled`, and `wow.http.openapi.springdoc.*` for springdoc |

## Security, operations and analytics

| 9.x | V10 |
| --- | --- |
| `wow.cosec.*`, `wow.cosec.abac.*` | `wow.cosec.*`: `tenant-level`, `cross-scope`, `service`, `authorizer`, `trust-injected-app` ([Secure a Deployment](../operate/security)); query ABAC is `wow.query.abac.*` |
| `wow.compensation.*`, the compensation service's own keys | `wow.subscription.parked.*` per node, and `wow.subscriptions.[<processor.function>].parked.*` per subscription; `wow.ops.*` for the operator routes, the webhook and publishing to the ops service ([Platform Services](./platform)) |
| `wow.bi.script.*` | Removed with BI sync: the ClickHouse sink, `wow.clickhouse.sink.*` ([ClickHouse](../operate/clickhouse)) |
| A fixed tenant per aggregate in code | `wow.aggregates.[<context>.<aggregate>].tenant` |

New in V10, with no 9.x counterpart: `wow.log.partitions` (fixed once the log holds entries), `wow.internal.*` (the internal port, 9090 by default), `wow.node.*`, `wow.wait.*`, `wow.tenant.default`, `wow.health.*`, `wow.timers.*` and `wow.standby`.
