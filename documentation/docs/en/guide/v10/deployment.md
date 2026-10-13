---
title: Deploying a V10 Service
description: Backends, the internal port, several nodes, Kafka topics, ClickHouse, shutdown, personal data in events and the shared tenant space of a Wow V10 service.
outline: deep
---

# Deploying a V10 Service

## Choose a backend

A node keeps its log, snapshots, checkpoints, leases, failure ledger, reservations and timers in one backend, chosen by `wow.backend`:

| Backend | Module | The application provides | Nodes |
| --- | --- | --- | --- |
| `memory` | None | Nothing | One; its data does not outlive it, and the host warns at startup |
| `postgres` | `wow-postgres` (brings the PostgreSQL driver) | A `javax.sql.DataSource` bean | As many as share the database |
| `mongo` | `wow-mongo` (brings the sync driver) | A `com.mongodb.client.MongoClient` bean; `wow.mongo.database` (`wow`) | As many as share the database; MongoDB must be a replica set, since appends are transactions |

`auto`, the default, picks PostgreSQL when its module and a `DataSource` are there, else MongoDB, else memory. An application with a `BackendFactory` bean of its own replaces the selection.

A `DataSource` bean on PostgreSQL, as the shop's `postgres` profile declares it:

```kotlin
@Configuration(proxyBeanMethods = false)
class PostgresDataSource {
    @Bean(destroyMethod = "close")
    fun dataSource(environment: Environment): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = environment.getRequiredProperty("spring.datasource.url")
            username = environment.getProperty("spring.datasource.username")
            password = environment.getProperty("spring.datasource.password")
        },
    )
}
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/catalog
    username: postgres
    password: postgres
wow:
  backend: postgres
```

The host creates its tables and collections at startup. The log and the snapshots are stored per aggregate type (`wow_log__<context>__<aggregate>`, `wow_snapshot__<context>__<aggregate>`); the timers per context; checkpoints, leases, the ledger and reservations are shared. `wow.log.partitions` (64) is fixed once a context's log holds entries: choose it before the first deployment.

## Run several nodes

On PostgreSQL or MongoDB, several nodes of one service share the work: each partition of a context's log has one owner at a time, chosen by leases in the database (no ZooKeeper, no consumer group). A command arriving at any node is forwarded to its partition's owner over the internal port; when the owner cannot be reached, the receiving node runs it itself, and the version check keeps the result correct. Give each node its own `server.port` (when they share a host), `wow.internal.port` and `wow.node.id`.

## The internal port

Each node serves a second HTTP/2 (h2c) port, `wow.internal.port` (9090), for the cluster channel (forwarded commands, new log entries) and the wait signals: a node that executes a command, or a context that handles a saga's command, signals the node where the wait started on that port.

- **It is not authenticated** (decision D173). The service must run in a private network, and the internal port must never be exposed outside it: no public load balancer, ingress or port mapping points at it.
- **The nodes must reach each other on it**, and so must the nodes of other services that wait across contexts: a wait that follows a command into another context is signalled by that context's nodes. Allow the port between the service's nodes and between services that share waits, and nowhere else.
- **Bind it to the private interface**: the port binds every interface unless `wow.internal.host` names one. Set `wow.internal.host` to the node's private address, and `wow.node.address` when the address other nodes use differs (behind NAT, in a container).
- Every node of a service, in a rolling upgrade too, must run adjacent minor versions of one major version: the internal protocol is compatible only within that range.

## Kafka

With `wow-kafka` and `wow.kafka.bootstrap-servers`, the node uses these topics (each after `wow.kafka.topic-prefix`):

| Topic | Written by | Read by |
| --- | --- | --- |
| `wow.<context>.<aggregate>.state` | This context's Sink, one record per commit of each published aggregate | Other contexts' handlers of the stream |
| `wow.<context>.commands` | Other contexts' sagas and timers | This context, with `wow.kafka.command-intake: true` |
| `wow.<context>.receipts` | Other contexts, for the commands of this context they rejected | This context's `@OnRejected` handlers |

A node whose sagas send commands to another context does not start without Kafka (or another command transport). The node does not create topics. Create them before the first start; the command topic must have exactly `wow.log.partitions` partitions (64 by default), or a node with `command-intake` does not start:

```bash
kafka-topics --bootstrap-server localhost:9092 --create --topic wow.inventory.commands --partitions 64
```

The subscriptions of other contexts' streams keep their offsets in the service's checkpoints, with partitions assigned by its leases: there is no consumer group to manage. `wow.kafka.properties.*` passes client settings such as `security.protocol` to every producer and consumer.

## ClickHouse

With `wow-clickhouse` and `wow.clickhouse.endpoint`, the Sink writes each published stream to ClickHouse and the datasets are served from there. `wow.clickhouse.sink.ddl: auto` creates the tables and views; use `validate` where operators own the schema. `history.enabled: false` per aggregate keeps the current state only, and `history.ttl` bounds how long each commit's state is kept.

## Shutdown and health

A stopping node stops taking new commands (`wow.node.closing`), hands off its partitions after their commands in flight (`wow.cluster.drain-timeout`), lets running waits finish (`wow.wait.max-timeout`) and saves its checkpoints. The host gives Spring Boot's shutdown phase their sum unless `spring.lifecycle.timeout-per-shutdown-phase` is set; give the platform's termination grace period at least as much.

## Personal data in events

Events are facts and are never changed: the log keeps them for good, and V10 offers neither event erasure nor crypto-shredding (decision D175). Plan for it in the model:

- Keep personal data (names, addresses, phone numbers, free text about a person) out of events where you can. Put it in a read model you can delete from (a projection's table, another service), and keep an opaque id in the event.
- When a command needs personal data to decide, let the decide function read it through a service rather than carrying it in the event.
- The operator of a command is recorded as an opaque id only. Request facts copied into events (`wow.http.request-facts`) must not be personal data either; IP addresses and user agents are not recorded.
- Snapshots and ClickHouse tables are derived from the log: deleting a row there does not delete the fact.

## One tenant space

All the contexts of one system share one tenant space (decision D180). A tenant id crosses services as it is: in a saga's command (`AggregateType(...).ref(event.ref.tenantId, id)`), in the published streams, in receipts and in timers. There is no tenant mapping between contexts, so give every service the same tenant ids for the same customers, and resolve the caller's tenant the same way in each service's `CallerResolver`. A fixed tenant for an aggregate type (`wow.aggregates.[<context>.<aggregate>].tenant`) puts its commands and state reads in that tenant, whoever the caller is.
