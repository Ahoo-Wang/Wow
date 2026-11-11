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

When a node joins or leaves, about one partition in N moves, and its subscriptions go on from their checkpoints on the new owner. A node that dies holds its leases until they expire, `wow.cluster.lease-ttl` (10 s): that is how long its partitions wait for a new owner. A command for an aggregate whose partition has no reachable owner runs on the node that received it; the aggregate's version keeps it correct, and the owner drops its cached state of the aggregate as soon as its subscriptions read that commit, so its next command decides on the log instead of conflicting (`wow.state_cache.invalidations`).

## Commands go to the owner

A command can arrive at any node; the node forwards it to the partition's owner over the internal port, so each aggregate's commands run one after another on one node, from its cached state. Forwarding costs one hop inside the cluster. A load balancer cannot route by aggregate, and does not need to.

## Limits a node enforces

A node bounds what it accepts and tells callers to come back later instead of queuing without end:

| Limit | Default | Beyond it |
| --- | --- | --- |
| `wow.executor.max-in-flight`: commands in flight on the node | 4,096 | The command is refused with `wow.command.busy` (429, with `Retry-After`) |
| `wow.executor.lane-capacity`: commands waiting for one aggregate | 1,024 | The same, for that aggregate |
| A store's concurrent queries | A quarter of a pool the appends share (at least 2), adapting up to half of it; all of a query pool of its own; 16 when the pool does not say its size | The query is refused with `wow.query.busy` (429) |
| `wow.query.dataset.max-concurrent`: dataset queries at once | 8, a fixed bound, not adaptive | The query waits in a short queue, then is refused with `wow.query.busy` |

A refusal with `Retry-After` is backpressure, not an error: clients retry later, and a saga that meets it retries the entry and slows its partition down, parking the aggregate only after 20 refusals of one entry (`wow.subscription.backpressure.max-attempts`) ([Failures and Retries](../guide/failures#how-a-failure-is-classified)).

**Queries never starve appends.** Each store's snapshot and `events` queries run under a bound of their own, and so do the long replays of its logs: a state read's, and a subscription's rebuild of a state that replays more than one page of the log (512 commits). Unless `wow.query.max-concurrent` fixes it, the bound adapts to the store's write pressure: it halves while appends slow down or wait for connections, and grows back when they recover. A separate pool for the queries, or a read replica, removes the contention altogether ([PostgreSQL](./postgresql#size-the-pools)).

## Subscriptions

A partition's subscriptions run on its owner, each at most `wow.subscription.max-in-flight` (1,024) entries ahead of its checkpoint. A subscription that falls behind does not take the node out of service: it shows as degraded health and a growing `wow.subscription.backlog`. Its handler's latency is usually the cause: a slow external call, or writes to a read model's store that need an index. More nodes spread its partitions; a faster handler helps more.

A subscription reads the state after each commit from the states the node keeps as it commits (`wow.shared-states`). One that falls further behind than they reach rebuilds the state from the aggregate's snapshot, or replays its log. The rebuilds of one aggregate run one at a time across the node's subscriptions: the others wait for the first, then go on from the state it rebuilt. A rebuild that replays more than one page of the log (512 commits) runs under the query bound of the log's store; beyond it, the subscription retries the entry later as backpressure. A shorter one is one read of the store, as a command's load of a state on a cache miss is, and runs at once. `wow.shared_state.rebuilds` counts the rebuilds, gated or not and whether they ran or were refused, and `wow.shared_state.rebuild.wait` times a gated one's wait for the bound ([Observability](../reference/observability)).

After a backlog, a history migrate-data imported or a long stop, a node catches up at most `wow.subscription.max-catch-ups` (2) partitions at a time. A partition behind the head of its log waits for its turn at its checkpoint, so the commands the node takes meanwhile keep their connections to the store. On the migration rehearsal (50,000 orders, 64 partitions on PostgreSQL, a steady 50 commands a second during the catch-up):

| `max-catch-ups` | Commands failed, of 6,000 | Worst one-second p99 of the commands | Catch-up |
| --- | --- | --- | --- |
| 2, the default | none | 2.0 to 3.7 s | 35 to 64 s |
| 4 | none | 4.5 to 10.2 s | 38 to 73 s |
| 8 | 48 in one run of four | 4.2 to 12 s | 41 to 93 s |
| 0, no bound | 772 to 2,495 | 21 to 30 s | 58 to 96 s |

More partitions at once did not catch up sooner, since they share the same store: on a store slower to answer it took longer. They raise the latency of the commands that come meanwhile, up to the timeout of a command that waits for its commit. A higher value suits a store with connections to spare and no traffic during the catch-up; 0 removes the bound.

## What to watch

| As load grows | Watch | Then |
| --- | --- | --- |
| Command latency rises | `wow.command.duration`, the store's append latency | Add nodes if the store keeps up; otherwise scale the store |
| Commands are refused | `wow.command.refusals` with `error.type=wow.command.busy` | Add nodes, or find the slow store or aggregate |
| One aggregate is hot | Its lane's refusals | Split its work across aggregates: one aggregate's commands are serial by design |
| Subscriptions fall behind | `wow.subscription.backlog`, `wow.subscription.lag` | Speed up the handler, add nodes |
| Queries are refused | `wow.query.limit.current`, `wow.query.duration` | A query pool or replica, narrower queries, indexes |

[Health, Metrics and Tracing](./observability) lists the metrics. [Performance](./performance) gives the benchmarks' figures and what they mean for sizing.
