---
title: Scaling and Capacity
description: How a service scales across nodes, how many partitions to choose, the limits a node enforces on commands, queries and subscriptions, and what to watch as load grows.
outline: deep
---

# Scaling and Capacity

A service scales by adding nodes: they split its partitions among them, each runs the commands and subscriptions of the partitions it owns, and the database underneath carries the appends of all of them. This page explains what to size, the limits a node enforces so that it degrades instead of falling over, and where the next bottleneck usually is. [Deployment Model](./topology#services-and-nodes) explains partitions and ownership.

## Nodes and partitions

Each context's log has a fixed number of partitions, `wow.log.partitions`, 64 by default. Each partition has one owner at a time, which runs every command for the partition's aggregates and every subscription over it. So:

- **More nodes, more throughput**, as long as the database keeps up: with N nodes, each owns about 64 / N partitions.
- **At most one node per partition does work.** A node beyond the number of partitions owns none and only forwards commands. 64 partitions suit most services; choose a larger power of two, up to 4,096, for a service that may need more nodes than that.
- **The count is fixed** once the log holds entries, so it is chosen before the first deployment.

When a node joins or leaves, about one partition in N moves, and its subscriptions go on from their checkpoints on the new owner. A node that dies holds its leases until they expire, `wow.cluster.lease-ttl` (10 s): that is how long its partitions wait for a new owner. A command for an aggregate whose partition has no reachable owner runs on the node that received it; the aggregate's version keeps it correct.

## Commands go to the owner

A command can arrive at any node; the node forwards it to the partition's owner over the internal port, so each aggregate's commands run one after another on one node, from its cached state. Forwarding costs one hop inside the cluster. A load balancer cannot route by aggregate, and does not need to.

## Limits a node enforces

A node bounds what it accepts and tells callers to come back later instead of queuing without end:

| Limit | Default | Beyond it |
| --- | --- | --- |
| `wow.executor.max-in-flight`: commands in flight on the node | 4,096 | The command is refused with `wow.command.busy` (429, with `Retry-After`) |
| `wow.executor.lane-capacity`: commands waiting for one aggregate | 1,024 | The same, for that aggregate |
| A store's concurrent queries | Half the appends' connection pool on PostgreSQL, adaptive | The query is refused with `wow.query.busy` (429) |
| `wow.query.dataset.max-concurrent`: dataset queries at once | A fixed bound, not adaptive | The query waits in a short queue, then is refused with `wow.query.busy` |

A refusal with `Retry-After` is backpressure, not an error: clients retry later, and a saga that meets it slows its partition down instead of parking anything ([Failures and Retries](../guide/failures#how-a-failure-is-classified)).

**Queries never starve appends.** Each store's snapshot and `events` queries run under a bound of their own. Unless `wow.query.max-concurrent` fixes it, the bound adapts to the store's write pressure: it halves while appends slow down or wait for connections, and grows back when they recover. A separate pool for the queries, or a read replica, removes the contention altogether ([PostgreSQL](./postgresql#size-the-pools)).

## Subscriptions

A partition's subscriptions run on its owner, each at most `wow.subscription.max-in-flight` (1,024) entries ahead of its checkpoint. A subscription that falls behind does not take the node out of service: it shows as degraded health and a growing `wow.subscription.backlog`. Its handler's latency is usually the cause: a slow external call, or writes to a read model's store that need an index. More nodes spread its partitions; a faster handler helps more.

## What to watch

| As load grows | Watch | Then |
| --- | --- | --- |
| Command latency rises | `wow.command.duration`, the store's append latency | Add nodes if the store keeps up; otherwise scale the store |
| Commands are refused | `wow.command.refusals` with `error.type=wow.command.busy` | Add nodes, or find the slow store or aggregate |
| One aggregate is hot | Its lane's refusals | Split its work across aggregates: one aggregate's commands are serial by design |
| Subscriptions fall behind | `wow.subscription.backlog`, `wow.subscription.lag` | Speed up the handler, add nodes |
| Queries are refused | `wow.query.limit.current`, `wow.query.duration` | A query pool or replica, narrower queries, indexes |

[Health, Metrics and Tracing](./observability) lists the metrics.
