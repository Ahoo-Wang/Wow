---
title: Kafka
description: What Wow uses Kafka for, the topics each context owns, how to create and size them, and the settings that keep the transport's guarantees.
---

# Kafka

Kafka carries what crosses a service's boundary: the streams a context publishes, the commands other contexts send it, and the receipts that come back. Inside a service, the log is the bus and Kafka is not involved. This page explains which topics a service uses, how to create them, and how to configure the transport.

## When a service needs Kafka

A service needs Kafka when one of these holds:

- it publishes its aggregates' streams for other contexts or external systems;
- it subscribes to another context's published stream;
- its sagas send commands to another context, or receive their receipts;
- other contexts send commands to it;
- an aggregate type's commands are routed through Kafka for durable intake.

A node whose sagas send commands to another context does not start without a command transport. For local development, `wow.topics.in-memory: true` keeps the topics in the JVM instead, so services in one process reach each other without a broker. Those topics keep every record they are sent for the life of the JVM: under sustained load they fill the heap, so they are for trying a service out, never for a load test or a deployment.

The transport is wired when the `wow-kafka` module is on the classpath and `wow.kafka.bootstrap-servers` is set. The eShop's ordering service connects with its `kafka` profile:

<!-- sample: example/eshop/ordering/ordering-server/src/main/resources/application.yaml#kafka -->

```yaml
wow:
  kafka:
    bootstrap-servers: localhost:9092
    # Execute the commands other contexts send to wow.eshop-ordering.commands (the payment's), which has as many
    # partitions as the context's log (wow.log.partitions, 64 by default).
    command-intake:
      enabled: true
```

## Topics

Each context owns its topics; the topics of other contexts are theirs.

| Topic | Written by | Read by |
| --- | --- | --- |
| `wow.<context>.<aggregate>.state` | This context's Kafka sink, one record per commit of each published aggregate | Other contexts' subscriptions, external consumers |
| `wow.<context>.commands` | Other contexts' sagas and timers; this context's own nodes for aggregate types routed through Kafka | This context |
| `wow.<context>.receipts` | Other contexts, when they reject a command this context sent, or commit one it asked a receipt for | This context's sagas |

An aggregate is published by default; it can stay unpublished, publish without its state, or name another topic. `wow.kafka.topic-prefix` puts a prefix before every topic name, such as `staging.`, on both the writing and the reading side. Every record is an envelope keyed by the aggregate, so one aggregate's records stay in order ([Wire protocols](../reference/protocols#kafka-envelope)).

### Partitions

- **A command topic has exactly as many partitions as its context's log** (`wow.log.partitions`). A command's partition in the topic is its aggregate's partition in the log, so the node that reads it is the node that owns the aggregate. With another count, commands would reach nodes that do not own their aggregates, so a node that reads a command topic checks the count at startup and refuses to start on a mismatch. Never add partitions to a command topic.
- **A state or receipts topic** can have any number of partitions, and partitions can be added while services run. A reading node looks for new partitions every `wow.kafka.topics.partition-refresh` (30 seconds) and reads them from their beginning, without a restart.

### Creating topics

At startup, a node checks the topics its contexts own, as `wow.kafka.ddl` says; it follows `wow.ddl` when not set, so the stores and the topics share one setting:

| `ddl` | At startup |
| --- | --- |
| `auto` (default) | The node first creates the missing topics, with the log's partition count, `wow.kafka.topics.replication-factor`, `wow.kafka.topics.min-insync-replicas` and `wow.kafka.topics.configs.*`, then validates. It never alters an existing topic. |
| `validate` | The node does not start while one of its topics is missing, or its command topic does not have exactly the log's partitions. The message names each such topic. |
| `none` | The node creates and checks nothing: the topics are someone else's. |

`auto` suits development and tests. In production, create the topics yourself and set `wow.ddl: validate` (or `wow.kafka.ddl` alone), which catches a missing one at the first start. For the eShop's ordering context:

```bash
kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.commands --partitions 64
kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.receipts --partitions 64
kafka-topics --bootstrap-server kafka:9092 --create --topic wow.eshop-ordering.order.state --partitions 64
```

No node ever creates a topic as a side effect of reading it, so a stream that does not exist yet is waited for, not created with the broker's default partition count.

## Durability

A command accepted on a command topic must survive the loss of a broker. That holds only when the topic's replication factor and `min.insync.replicas` are both at least 2. At startup, a node warns about each command topic of its contexts that is below that, and about a configuration that would create one, unless a development profile is active (`dev`, `local`, `test` or `demo`).

The producer is idempotent and waits for every in-sync replica (`acks=all`), whatever `wow.kafka.properties` says.

## Durable command intake

By default a command is routed over the cluster channel to the owner of its aggregate, and `ACCEPTED` only means it passed admission: a node that stops before running it loses it. For commands that must not be lost once accepted, such as asynchronous intake or absorbing load peaks, route their aggregate type through Kafka with `wow.aggregates.[<context>.<aggregate>].routing: kafka`:

- The node that admits such a command writes it to the context's command topic and waits for the broker. A request that waits only for `accepted` is answered 202 once the broker has acknowledged it; from then on, the command is durable.
- The owner of the aggregate's partition reads it from the topic and executes it, so one aggregate's commands keep their order. A longer wait, such as `snapshot`, goes on as usual.
- If the broker does not acknowledge within the wait's timeout, the wait ends at `ACCEPTED`, failed. A retry with the same request id is not decided twice.

Only commands admitted at the edge take this route. A command a saga or a timer sends is already durable, because its sender retries until it commits.

A context whose aggregate types are routed through Kafka reads its command topic whatever `wow.kafka.command-intake.enabled` says. Otherwise, set `wow.kafka.command-intake.enabled: true` on a service that should execute the commands other contexts send it.

## Reading other contexts' streams

A service reads other contexts' topics without a consumer group. Its leases assign the topic's partitions to its nodes, the same way they assign the log's, and the offsets are kept in the service's own checkpoint store, beside its other checkpoints. One consumer per node and topic feeds every subscription that reads the topic. So there is no consumer group to manage, reset or monitor in Kafka; a subscription's position is its checkpoint.

When a partition no longer holds the offset a subscription is to read from, because retention deleted it or the topic was created again, that is an **offset gap**. `wow.kafka.offset-gap` decides what happens:

- `fail` (default): the read fails and the subscription stays where it is, logging errors;
- `earliest`: the subscription reads on from the earliest retained offset, skipping what was lost.

Either way the gap is logged as an error and counted in the metric `wow.kafka.offset.gaps`; alert on it. Set each topic's retention longer than any subscription may fall behind.

To recover a subscription stopped by a gap, set `wow.kafka.offset-gap: earliest` and restart its nodes: the read resumes from the earliest retained offset. The skipped range is lost to that subscription, and stays counted in `wow.kafka.offset.gaps`. Set the option back to `fail` afterwards to keep the strict default. When the loss is not acceptable and what was published is still complete elsewhere, rebuild under a new subscription name instead.

## Client settings

`wow.kafka.properties.*` passes settings to every producer and consumer as they are, such as `security.protocol` and SASL settings, `client.id` or `linger.ms`. A few settings belong to Wow and cannot be overridden, because the guarantees rest on them: the idempotent producer with `acks=all`, consumers without a group that commit nothing and reset no offset on their own, and no topic creation on read.
