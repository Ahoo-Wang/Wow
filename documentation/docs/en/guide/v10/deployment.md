---
title: Deploying a V10 Service
description: Backends, the internal port, several nodes, Kafka topics, ClickHouse, shutdown, Kubernetes manifests, personal data in events and the shared tenant space of a Wow V10 service.
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
| `wow.<context>.commands` | Other contexts' sagas and timers; this context's nodes, for the types routed through Kafka | This context, with `wow.kafka.command-intake: true` or a type routed through Kafka |
| `wow.<context>.receipts` | Other contexts, for the commands of this context they rejected | This context's `@OnRejected` handlers |

A node whose sagas send commands to another context does not start without Kafka (or another command transport).

### Kafka Router

By default a node routes a command over the cluster channel to the owner of its aggregate's log partition (`wow.cluster.router`). An `accepted` answer then only means the command passed admission: a node that stops before running it loses it. For commands that must not be lost once accepted, such as asynchronous intake or load peaks, route their aggregate type through Kafka (decision D143):

```yaml
wow:
  kafka:
    bootstrap-servers: kafka:9092
  aggregates:
    "[eshop-ordering.order]":
      routing: kafka
```

The node that admits such a command writes it to the context's command topic, in the partition of its aggregate's log partition, and waits for the broker. The owner of that log partition reads it from there and executes it, so one aggregate's commands keep their order. A wait plan of only `accepted` is answered **202** once the broker acknowledged the command: it is durable from then on, and the node may die at once. A longer wait (`committed`, `snapshot`, a saga's hops) goes on as usual, by the signals of the node that executed it. A broker that does not acknowledge within the wait's timeout ends the wait at `accepted`, failed; a retry with the same request id is not decided twice.

The context runs its command intake whatever `wow.kafka.command-intake` says, so its command topic must have exactly `wow.log.partitions` partitions. Only commands admitted through the gateway (HTTP, `CommandGateway`) take this route. A saga's, a timer's or an intake's command is routed by `wow.cluster.router`: it is already durable, because its subscription or timer retries until it is committed.

### Topic management

Each context owns three kinds of topics: its command topic (when it takes commands), its receipts topic (when its sagas have `@OnRejected` handlers) and the state topic of each aggregate it publishes. The topics of other contexts are theirs. At startup the node checks the topics it owns, as `wow.kafka.topics.ddl` says (decision D168):

| `wow.kafka.topics.ddl` | At startup |
| --- | --- |
| `validate` (default) | The node does not start when one of its topics is missing, or its command topic does not have exactly `wow.log.partitions` partitions. The message names each such topic |
| `auto` | The node first creates the missing topics with `wow.log.partitions` partitions, `wow.kafka.topics.replication-factor` and `wow.kafka.topics.configs.*`, then validates. An existing topic is never altered |
| `none` | The node checks nothing, except that a command topic it reads still has exactly `wow.log.partitions` partitions |

The command topic's partition count is always checked, because a command's partition in it is its aggregate's log partition: with another count, commands would reach nodes that do not own their aggregates. Use `auto` for development and tests. In production, operators create the topics, and the default `validate` catches a missing one at the first start:

```bash
kafka-topics --bootstrap-server localhost:9092 --create --topic wow.inventory.commands --partitions 64
kafka-topics --bootstrap-server localhost:9092 --create --topic wow.inventory.receipts --partitions 64
kafka-topics --bootstrap-server localhost:9092 --create --topic wow.inventory.stock.state --partitions 64
```

The node never asks Kafka to create a topic as a side effect of reading it (`allow.auto.create.topics=false`), so a stream that does not exist yet is waited for, not created with the broker's default partitions.

Partitions may be added to a state or receipts topic while the service runs: the node looks for them every `wow.kafka.topics.partition-refresh` (30 seconds) and reads them from their beginning, without a restart. Never add partitions to a command topic.

The subscriptions of other contexts' streams keep their offsets in the service's checkpoints, with partitions assigned by its leases: there is no consumer group to manage. `wow.kafka.properties.*` passes client settings such as `security.protocol` to every producer and consumer.

## ClickHouse

With `wow-clickhouse` and `wow.clickhouse.endpoint`, the Sink writes each published stream to ClickHouse and the datasets are served from there. `wow.clickhouse.sink.ddl: auto` creates the tables and views; use `validate` where operators own the schema. `history.enabled: false` per aggregate keeps the current state only, and `history.ttl` bounds how long each commit's state is kept.

## Shutdown and health

A stopping node stops taking new commands (`wow.node.closing`), hands off its partitions after their commands in flight (`wow.cluster.drain-timeout`), lets running waits finish (`wow.wait.max-timeout`) and saves its checkpoints. The host gives Spring Boot's shutdown phase their sum unless `spring.lifecycle.timeout-per-shutdown-phase` is set; give the platform's termination grace period at least as much.

The host has no health endpoint of its own. Its public port opens only once the node has started (backends, internal port, partitions) and closes first when it stops, so a probe of the public port tells whether the node takes traffic. The internal port stays open until the node has stopped, so a probe of it tells whether the node is alive, even while it drains. With Spring Boot Actuator on the classpath, `/actuator/health/readiness` reports `REFUSING_TRAFFIC` once the node starts to stop.

## Kubernetes

[`deploy/v10/eshop-ordering`](https://github.com/Ahoo-Wang/Wow/tree/v10/deploy/v10/eshop-ordering) deploys the eShop ordering service as two nodes on PostgreSQL and Kafka, in plain manifests applied with `kubectl apply -k`. Use it as a template for a V10 service. Its README covers the image build and the Kafka topics.

| Concern | How the manifests handle it |
| --- | --- |
| Node id | `wow.node.id` is the pod's name (`metadata.name`), so a replaced pod joins as a new member |
| Internal port | `wow.internal.host` is the pod IP, so the internal port is bound to the private interface only. `wow.node.address` is the pod's name under a headless service (`<pod>.eshop-ordering-cluster.<namespace>.svc.cluster.local:9093`). That service publishes nodes that are not ready, so a node that is starting or draining still gets its peers' forwarded commands and signals |
| Who reaches it | Only the internal port's headless service points at it; no `Service` of type `LoadBalancer` or ingress does. A `NetworkPolicy` lets in only the pods labelled `app.kubernetes.io/part-of: eshop` (decision D173) |
| HTTP | A plain `Service` on the public port |
| PostgreSQL and Kafka | A `ConfigMap` of environment variables (`SPRING_DATASOURCE_URL`, `WOW_KAFKA_BOOTSTRAPSERVERS`, `WOW_LOG_PARTITIONS`, the active profiles), and a `Secret` with the database user and password |
| Probes | Startup and readiness probe the public port, liveness the internal port |
| Shutdown | `terminationGracePeriodSeconds: 90`: a 5 s `preStop` pause, then `wow.wait.max-timeout` (60 s) and `wow.cluster.drain-timeout` (10 s), with room to spare. Raise it when you raise those settings |

## Personal data in events

Events are facts and are never changed: the log keeps them for good, and V10 offers neither event erasure nor crypto-shredding (decision D175). Plan for it in the model:

- Keep personal data (names, addresses, phone numbers, free text about a person) out of events where you can. Put it in a read model you can delete from (a projection's table, another service), and keep an opaque id in the event.
- When a command needs personal data to decide, let the decide function read it through a service rather than carrying it in the event.
- The operator of a command is recorded as an opaque id only. Request facts copied into events (`wow.http.request-facts`) must not be personal data either. The public port does record each command's client address and `User-Agent` (`remote_ip`, `user_agent`), as 9.x did, for audit: they are personal data, written into the event stream, which is never deleted, so they cannot be erased once recorded. Set `wow.http.client-facts: false` to record neither.
- Snapshots and ClickHouse tables are derived from the log: deleting a row there does not delete the fact.

## One tenant space

All the contexts of one system share one tenant space (decision D180). A tenant id crosses services as it is: in a saga's command (`AggregateType(...).ref(event.ref.tenantId, id)`), in the published streams, in receipts and in timers. There is no tenant mapping between contexts, so give every service the same tenant ids for the same customers, and resolve the caller's tenant the same way in each service's `CallerResolver`. A fixed tenant for an aggregate type (`wow.aggregates.[<context>.<aggregate>].tenant`) puts its commands and state reads in that tenant, whoever the caller is.
