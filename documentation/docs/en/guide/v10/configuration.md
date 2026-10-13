---
title: V10 Configuration Keys
description: Every wow.* configuration key of the Wow V10 Spring Boot host, with its default, from WowProperties, QueryProperties, KafkaProperties and ClickHouseProperties.
outline: deep
---

# V10 Configuration Keys

The V10 host reads the `wow.*` namespace, bound by Spring Boot from `application.yaml`, the environment or the command line. No key keeps a 9.x meaning. This page lists every key of the four property classes of `wow-spring-boot`: `WowProperties` (`wow.*`), `QueryProperties` (`wow.query.*`), `KafkaProperties` (`wow.kafka.*`) and `ClickHouseProperties` (`wow.clickhouse.*`). Durations take Spring Boot's forms (`500ms`, `10s`, `1m`, `PT10S`).

A key whose name holds a dot of its own (a subscription or an aggregate type) is bracketed, and quoted in YAML:

```yaml
wow:
  subscriptions:
    "[OrderStockSaga.onOrderShipped]":
      start: beginning
  aggregates:
    "[example.cart]":
      tenant: shop
```

## Service and node

| Key | Default | Means |
| --- | --- | --- |
| `wow.service` | `spring.application.name` | The service's name: of its lease groups, its wait origins and its Manifest. One of the two must be set |
| `wow.node.id` | The host name and a random suffix | This node's id among the service's nodes; a new one each start when unset |
| `wow.node.address` | This host's address and `wow.internal.port` | The `host:port` at which the other nodes reach this node's internal port |
| `wow.internal.port` | `9090` | The internal port: the cluster channel and the wait signals. `0` lets the system choose; the node then learns it once bound |
| `wow.internal.host` | Every interface | The address the internal port binds |
| `wow.internal.connect-timeout` | `1s` | How long a peer has to accept a connection of the cluster channel or the signal sender |
| `wow.internal.signal-queue-capacity` | `1024` | The wait signals queued per origin node before they are dropped |

## Backend and log

| Key | Default | Means |
| --- | --- | --- |
| `wow.backend` | `auto` | `auto`, `memory`, `postgres` or `mongo`. `auto` takes PostgreSQL when `wow-postgres` is on the class path and the application has a `DataSource` bean, else MongoDB when `wow-mongo` is and it has a `MongoClient` bean, else memory |
| `wow.mongo.database` | `wow` | The database of the log and the stores on MongoDB |
| `wow.log.partitions` | `64` | The partitions of each context's log; fixed once the log holds entries |
| `wow.log.append-queue.enabled` | The backend's preset: on for memory and MongoDB, off for PostgreSQL | Whether appends to a partition are batched |
| `wow.log.append-queue.max-batch` | `64` | The most appends one batch takes |

## Cluster

| Key | Default | Means |
| --- | --- | --- |
| `wow.cluster.router` | `local` on memory, `partitioned` otherwise | Where commands run: `partitioned`, on the owner of the aggregate's log partition, or `local`, on the node that took them. The memory backend refuses `partitioned` |
| `wow.cluster.lease-ttl` | `10s` | How long a partition lease lasts without renewal |
| `wow.cluster.renew-interval` | `3s` | How often a lease is renewed; less than the TTL |
| `wow.cluster.member-ttl` | `10s` | How long a membership lasts without a heartbeat |
| `wow.cluster.heartbeat-interval` | `3s` | How often a member heartbeats; less than its TTL |
| `wow.cluster.reconcile-interval` | `1s` | How often a node reconciles the partitions it should own |
| `wow.cluster.route-refresh-interval` | `1s` | How often the route table is read; a failed forward refreshes it at once |
| `wow.cluster.forward-timeout` | `1s` | How long a forward waits for the owner, capped by the command's deadline; then the command runs locally |
| `wow.cluster.max-in-flight-per-peer` | `1024` | The forwards one peer may have outstanding; one more runs locally |
| `wow.cluster.drain-timeout` | `10s` | How long a hand-off waits for the partition's commands in flight |
| `wow.cluster.lease-parallelism` | `16` | The lease calls that run at once |

## Commands and waits

| Key | Default | Means |
| --- | --- | --- |
| `wow.wait.default-timeout` | `10s` | How long a command whose request names no timeout waits |
| `wow.wait.max-timeout` | `60s` | The longest wait; a longer timeout is capped to it |
| `wow.wait.max-hops` | `4` | The most hops of a wait plan |
| `wow.executor.lane-capacity` | `1024` | The commands one aggregate may have waiting |
| `wow.state-cache.capacity` | `4096` | The aggregate states kept per context between commands; `0` keeps none |
| `wow.tenant.default` | `(0)` | The tenant of a caller that has none, such as the anonymous one |
| `wow.aggregates.[<context>.<aggregate>].tenant` | None | A fixed tenant for every aggregate of the type, whoever the caller is. A type the node does not serve is refused at startup |

Unless the application sets `spring.lifecycle.timeout-per-shutdown-phase`, the host sets it to `wow.wait.max-timeout + wow.cluster.drain-timeout`, so a stopping node has the time it needs; a smaller value the application sets is kept, with a warning.

## Subscriptions and timers

| Key | Default | Means |
| --- | --- | --- |
| `wow.subscription.max-in-flight` | `1024` | The entries one subscription may hold in one partition between its checkpoint and the last entry it took |
| `wow.subscription.checkpoint-interval` | `1s` | How often each subscription saves its checkpoint |
| `wow.subscription.stop-timeout` | `10s` | How long a stop waits for the handlers in flight |
| `wow.subscription.retry.max-attempts` | `5` | The calls of a failing handler, in all, before its aggregate is parked |
| `wow.subscription.retry.initial-backoff` | `100ms` | The wait after the first failure |
| `wow.subscription.retry.max-backoff` | `5s` | The longest wait between attempts |
| `wow.subscription.retry.multiplier` | `2.0` | How much longer each next wait is |
| `wow.subscription.read-retry.*` | `100ms` up to `5s`, `2.0` | The backoff of a failed log read or store write, retried until the node stops (`max-attempts` is not used) |
| `wow.subscriptions.[<subscription>].start` | The kind's default | `beginning`, `end`, or an ISO-8601 instant: where the subscription starts in a partition it has no checkpoint in. A name the node has no subscription of is refused at startup |
| `wow.timers.poll-interval` | `1s` | How often each owned partition reads the timers due soon |
| `wow.timers.lookahead` | `30s` | How far ahead the timers are read |
| `wow.timers.max-attempts` | `10` | The attempts a technical failure to send a scheduled command gets before it is parked under `wow.timers` |
| `wow.timers.expires-after` | None | When set, a timer found due longer ago than this is cancelled (`EXPIRED`) instead of sent |

## HTTP

| Key | Default | Means |
| --- | --- | --- |
| `wow.http.request-facts` | Empty | The request headers copied into a command's headers, and so into the event stream |
| `wow.http.errors.[<code>].status` | The Manifest's | The HTTP status of an error code |
| `wow.http.manifest.enabled` | `true` | Whether `GET /wow/manifest` is served |

The public port is Spring Boot's `server.port`.

## Query

| Key | Default | Means |
| --- | --- | --- |
| `wow.query.enabled` | `true` | Whether the query routes and gateways are wired |
| `wow.query.http.max-list-size` | `1000` | The largest `list` |
| `wow.query.http.default-list-size` | `100` | The limit of a `list` that names none |
| `wow.query.http.max-page-size` | `100` | The largest page |
| `wow.query.http.max-page-window` | `10000` | How deep a `paged` query may reach |
| `wow.query.http.max-filter-nodes` | `128` | The most nodes of a filter |
| `wow.query.http.max-filter-values` | `1000` | The most values of a filter |
| `wow.query.http.allow-expensive-operators` | `true` | Whether the expensive operators are allowed |
| `wow.query.http.max-residual-groups` | `10000` | The most groups the core reads to compute an aggregation's `HAVING` or metric sort that the backend cannot run itself |
| `wow.query.http.count-limit` | `10000` | The bound of a count and of a paged total |
| `wow.query.http.allow-exact-count` | `false` | Whether a query may ask for an exact total |
| `wow.query.http.timeout` | `30s` | The time an HTTP query may run |
| `wow.query.http.unindexed-sort-expensive` | `false` | Whether a sort the backend runs without an index counts as an expensive operator |
| `wow.query.in-process-timeout` | `30s` | The timeout of an in-process query, which has no budget |
| `wow.query.require-explicit-entry` | `false` | Whether a query must say whether it is HTTP or in-process; unspecified, it is taken as in-process |
| `wow.query.require-authenticated-scope` | `false` | Whether an HTTP query needs an authenticated scope that pins the model's required scope; off, a declared scope is trusted |
| `wow.query.abac.enabled` | `false` | Whether the caller's `abac.<key>` attributes are matched against the snapshots' `tags` |
| `wow.query.abac.require-principal-tags` | `false` | Whether an HTTP query whose caller has no ABAC tags is rejected, instead of matching every resource |
| `wow.query.abac.match-missing-tag-key` | `true` | Whether a resource that lacks one of the caller's tag keys matches, as a public resource |
| `wow.query.abac.attribute-prefix` | `abac.` | The prefix of the caller's attributes ABAC reads |
| `wow.query.sensitivity.display-comparable` | `true` | Whether filters and sorts may compare a `DISPLAY` value |
| `wow.query.index.ddl` | `auto` | How the snapshot backend manages the indexes of `@QueryIndex`: `auto` creates or rebuilds, `validate` reports, `none` does nothing |
| `wow.query.dataset.config-directory` | `config` | The working directory whose `wow/dataset/` holds dataset files, ahead of the class path's `META-INF/wow/dataset/` |

A limit of `0` is off.

## Kafka

Read when `wow-kafka` is on the class path; nothing is wired until `wow.kafka.bootstrap-servers` is set.

| Key | Default | Means |
| --- | --- | --- |
| `wow.kafka.bootstrap-servers` | None | The brokers. Set, the node publishes its streams, reads other contexts' streams and receipts, and sends commands to other contexts |
| `wow.kafka.properties.*` | Empty | Passed to every producer and consumer as they are (`security.protocol`, `client.id`, `linger.ms`, …), except the settings the transport's guarantees rest on |
| `wow.kafka.topic-prefix` | Empty | Put before every topic name, such as `staging.` for `staging.wow.shop.order.state` |
| `wow.kafka.poll-timeout` | `200ms` | The longest a consumer's poll waits |
| `wow.kafka.command-intake` | `false` | Whether each context the node hosts executes the commands of its topic `wow.<context>.commands`, which must exist with `wow.log.partitions` partitions |

## ClickHouse

Read when `wow-clickhouse` is on the class path; nothing is wired until `wow.clickhouse.endpoint` is set.

| Key | Default | Means |
| --- | --- | --- |
| `wow.clickhouse.endpoint` | None | The server's HTTP endpoint, such as `http://localhost:8123` |
| `wow.clickhouse.username` | `default` | The user |
| `wow.clickhouse.password` | Empty | Its password |
| `wow.clickhouse.database` | `default` | The database of the tables |
| `wow.clickhouse.sink.enabled` | `true` | Whether the Manifest's published streams are written to ClickHouse |
| `wow.clickhouse.sink.ddl` | `auto` | `auto` (create what is missing, then validate), `validate` or `none` |
| `wow.clickhouse.sink.batch-size` | `1000` | The most records one insert carries |
| `wow.clickhouse.sink.deduplication-window` | `1000` | How many recent inserts each table remembers, to drop a retried one |
| `wow.clickhouse.sink.aggregates.<context>.<aggregate>.history.enabled` | `true` | Whether every commit is kept; off keeps only the current state |
| `wow.clickhouse.sink.aggregates.<context>.<aggregate>.history.ttl` | None | How long the history is kept; forever when unset |
| `wow.clickhouse.query.enabled` | `true` | Whether the datasets are served from ClickHouse |
| `wow.clickhouse.query.metadata-ttl` | `1m` | How long a dataset table's columns are reused before they are read again |
| `wow.clickhouse.query.settings.<name>` | Empty | Server settings every dataset query runs under, such as `max_memory_usage` |
