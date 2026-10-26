---
title: Troubleshooting
description: Symptoms, their causes and what to do; a node that will not start, commands that time out or are refused, subscriptions that park or fall behind, partitions without an owner.
outline: deep
---

# Troubleshooting

Each section starts from what you see, names the usual causes, and says what to do. Every answer a node gives carries a code; [Error Codes](../reference/error-codes) lists them all.

## A node will not start

The host refuses to start rather than run with a setting that would quietly do the wrong thing. The log names the cause:

| The log says | Cause | Do |
| --- | --- | --- |
| No durable backend | No `DataSource` or `MongoClient` bean, outside a development profile and with an internal port that binds more than loopback | Set `spring.datasource.url` (with HikariCP on the class path) or `spring.mongodb.uri`, give the application its store bean, or set `wow.backend: memory` on purpose |
| The service's name is no name its nodes can state on the internal port | `wow.service`, or `spring.application.name` without it, holds a character other than letters, digits, `.`, `_` and `-`, or more than 253 | Rename the service, the same on every node and in the peers' `wow.http.services` |
| Unknown security configuration keys | A mistyped key under `wow.cosec.`, `wow.http.services.`, `wow.calls.` or `wow.internal.` | Fix the key ([Configure a Service](./configuration#keys-are-checked)) |
| A subscription, aggregate or context the node does not serve | A name under `wow.subscriptions`, `wow.aggregates` or `wow.contexts` that matches nothing | Fix the name, or remove the entry |
| No command transport | A saga sends commands to another context and the node has neither Kafka nor in-memory topics | Set `wow.kafka.bootstrap-servers` |
| A missing topic, or a command topic with the wrong number of partitions | `wow.kafka.topics.ddl: validate`, and the operator has not created the topic as the log needs it | Create the topic: a command topic has exactly `wow.log.partitions` partitions ([Kafka](./kafka#creating-topics)) |
| A structure of another format | A table created by an incompatible build of Wow | Run the migration of that major version; never edit `wow_format` |

## Commands time out

`wow.wait.timeout` (504) means the wait ran out, not that the command failed: **the command may have committed**. The problem names the last stage reached.

- Send it again **with the same request id**: a command that did commit is answered as a duplicate, with its original result.
- A wait for a later stage (`HANDLED`, a saga's hop, `SNAPSHOT`) waits for subscriptions too. A parked or lagging subscription on the chain makes it time out ([Waiting for Outcomes](../concepts/waits)).
- `wow.command.unknown` (503) means a forward to the owner got no answer in time: whether it committed is unknown, and a retry with the same request id is safe.

## Commands are refused

| Code | Cause | Do |
| --- | --- | --- |
| `wow.command.busy` (429) | The node or the aggregate has too many commands in flight | Retry after `Retry-After`; add nodes if it persists ([Scaling and Capacity](./scaling#limits-a-node-enforces)) |
| `wow.aggregate.version-mismatch` (409, 412 with `If-Match`) | The aggregate is not at the version the client expected | Read it again and decide whether to retry |
| `wow.command.contended` (409) | Concurrent writers kept winning the version | Retry with the same request id |
| `wow.command.request-id-reused` (409) | The request id was used by another command | Give each command its own request id |
| `wow.aggregate.forbidden` (403) | The aggregate belongs to another owner or space than the caller's | Check the caller's scope ([Secure a Deployment](./security)) |
| `wow.auth.forbidden` (403) | The authorizer refused the command for the caller | Check the caller's roles or policies |
| `wow.call.command-forbidden` (403) | A service called a command its entry under `wow.http.services` does not list | List the command for that service, on the target |
| `wow.storage.unavailable` (503) | A store could not be reached | Check the database; retry with the same request id |
| `wow.node.standby` (503) | The node is on standby for a migration's cut-over | Resume it at the cut-over ([Deployment Model](./topology#standby-for-a-migrations-cut-over)) |

## A subscription parks aggregates

`wow.subscription.parked` is above zero, and the ops console lists the aggregates. Each entry carries the error and the version the subscription stopped at.

- Fix the cause first: deploy the corrected handler, or bring back the outside system it calls.
- Then resume the aggregates: the subscription reads each one's stream again from where it stopped ([The Ops Service and Console](./ops-console)).
- A rejection of a command a saga sent parks the aggregate only when the saga has no `@OnRejected` handler for it ([Failures and Retries](../guide/failures)).

## A subscription stops for every aggregate

`wow.subscription.paused` is 1 for a partition: the subscription took a failure for an outage of something every aggregate needs, a store or a downstream service, and pauses the partition until it is back. Nothing is parked meanwhile. Find what is down from the subscription's log; the partition resumes by itself.

## A subscription falls behind

`wow.subscription.backlog` grows and the subscriptions health component is degraded. The handler is slower than the log grows: a slow external call, writes to a store that needs an index, or a downstream that pushes back (`wow.subscription.throttled`). Speed up the handler, then add nodes to spread its partitions ([Scaling and Capacity](./scaling#subscriptions)).

## A partition has no owner

The ownership health component is `DOWN`, or commands for some aggregates are slow while a node is gone. Leases of a node that died expire after `wow.cluster.lease-ttl` (10 s), then the partitions move. When they do not:

- The nodes cannot reach each other's internal port: check the network policy and `wow.node.address`, the address each node advertises.
- The lease store is down: the stores health component says so.

## A topic of another context

| Symptom | Cause | Do |
| --- | --- | --- |
| A node stays not ready after it starts | A topic it reads is still joining its lease group | Wait; or set `wow.health.topics.required: false` |
| `wow.kafka.offset.gaps` is above zero | Retention deleted offsets a subscription had not read | Raise the topic's retention; decide on `wow.kafka.offset-gap` ([Kafka](./kafka#reading-other-contexts-streams)) |
| `wow.subscription.undecodable` is above zero | Messages this version cannot decode | Upgrade the reading service's api, or the writer sends an unknown format |

## Queries are refused or slow

`wow.query.busy` (429) means the store's query bound is reached; it adapts down while the store's appends are under pressure. `wow.query.timeout` (504) means the query ran longer than its budget: narrow it rather than run it again. Give the queries a pool or a replica of their own ([PostgreSQL](./postgresql#size-the-pools)).
