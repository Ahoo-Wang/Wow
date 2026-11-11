---
title: Rebuild and Replay
description: Rebuild read models and snapshots from the log, the only fact; a new subscription from the beginning, snapshot rebuilds by revision or by request, and the access attributes of streams written before them.
outline: deep
---

# Rebuild and Replay

The log is the only fact. Snapshots, read models, the warehouse and search indexes are derived from it, so any of them can be rebuilt from it, without a migration script and while the service runs. This page lists the ways, from the most common.

## A read model: a new subscription

A projection's position is its subscription's checkpoint, so a read model is rebuilt by giving the projection a subscription without one:

1. Deploy the changed projection under a new name (a new class, or `@Name`), writing to a new table or index. A projection without a checkpoint starts at the beginning of the log and fills its store with the history; `wow.subscriptions.[<context>.<processor.function>].start` can start it at an instant instead: at the first entry of each partition committed at or after it, handling every entry after that one, whatever its commit time. Finding it reads the partition from the beginning once, on the first start, without handling what it reads.
2. Wait until it has caught up: its `wow.subscription.backlog` falls to zero and `wow.subscription.progress` reaches 1.
3. Switch the reads to the new store.
4. Remove the old projection, and drop its store.

The old and the new read model run side by side until the switch, so the reads never see a half-built model. A reactor or a saga is never replayed this way: a new one starts at the end of the log, so deploying it does not repeat effects or commands for the past.

## Snapshots: raise the state revision

When the stored snapshots no longer read as the state, or no longer hold what the state now derives, raise the aggregate's `@AggregateRoot(stateRevision = n)` and deploy ([Evolve Events](../guide/event-evolution#change-the-state-s-structure)). The service rebuilds the stored snapshots in place, in the background:

- The nodes of the new revision claim the snapshot table at it. During a rolling upgrade, the nodes of the old revision keep writing at theirs, and the store keeps them from overwriting the new revision's snapshots.
- One node at a time rewrites the table's snapshots from the log, page by page, and marks the table rebuilt when none is left.
- The rebuild is done when the table is marked rebuilt; the node logs `The snapshots of <type> in <store> are rebuilt at state revision <n>.`

## Snapshots: rebuild some on request

A snapshot that is wrong for another reason, a defect fixed since, say, is rewritten from the log on request:

```bash
curl -X POST "http://<service>/<context>/<aggregate>/wow.snapshot.rebuild" \
  -H "Content-Type: application/json" \
  -d '{"ids": ["SO-261031-1", "SO-261031-2"]}'
```

The route sends the system command `wow.snapshot.Rebuild` to the context's built-in rebuild aggregate, through admission like any command, so the authorizer decides who may send it; a service without a command authorizer refuses it on the public port (403 `wow.auth.forbidden`), never allowing it by default ([Authorize commands](./security#authorize-commands)). A built-in subscription then rewrites each named snapshot from the log, in every target store.

## A new snapshot store

Adding a snapshot target to an aggregate type (`wow.snapshot.stores`, or `wow.aggregates.[<context>.<aggregate>].snapshot.*`), an Elasticsearch index beside PostgreSQL, say, fills it from the beginning of the log: a new store's own snapshot subscription starts there, and for a type added to a store whose subscription is already past the beginning, a subscription of its own does, and stops once the target has caught up. Switching the primary target, the one queries read, moves no checkpoint.

## Access attributes

Each stream records the aggregate's access attributes after its commit, its owner and space among them, and so do the snapshots and the Sinks' records. When a commit changes them, the built-in subscription `wow.access` stamps the new ones over the aggregate's earlier streams, so its whole history is authorized by what the aggregate is now. An Elasticsearch store's events index follows them the same way: its Sink stamps a change over the documents it wrote before, and the walks below stamp the index with the log. Every copy keeps the attributes of the later version (`accessVersion`), whatever order the stamps arrive in.

Streams an import from 9.x appended carry none: under an access policy, an events query leaves those streams out until they are stamped, and so does an events query by a caller acting as an owner or in a space. The access walk of each aggregate type stamps them:

- **When it runs.** A node claims the walk of a type when the type's log records none, or records one of another access schema (the attributes the model declares, the type of each and whether it holds many) and no node of that schema is left. So a rolling deployment that declares a new attribute walks the log once the last node of the previous version has stopped. A type the log holds no stream of yet has no walk, and the walk creates no collection or table for it. The step that creates them, the type's first append or the query indexes a node makes as it starts, records the type's walk as completed for that node's schema, with nothing stamped: every stream a store this version created holds was committed with its attributes, so a new aggregate type or a new service is never walked, on any node. A store an import or a tool created records none, and the first run after its first append claims its walk. A node reads whether a type without a store has one now after one run, then after twice as many runs each time, up to 16 runs, about 80 seconds at the default interval. An import claims a type's walk once its first streams of that type are in, in place of one that stamped nothing, such as the walk the store's creation recorded, so the nodes walk them when they start or are activated from standby. A node that was already running keeps the walk it read until it restarts, so import only while the nodes are on standby, and if streams were imported into a service whose nodes were active, restart the nodes or run `migrate-data snapshot`, which stamps them. After a migration, `migrate-data snapshot` stamps the imported history itself and records each type's walk as completed, so the nodes do not walk it ([The access attributes](../migrate/data#_7-the-access-attributes)).
- **What it does.** One node at a time pages the type's aggregates and, one aggregate after another, stamps each one's whole history with the attributes of its current state and rewrites its snapshot, each in short statements of its own: the walk holds no lock or connection from one aggregate to the next, and at most one connection of the log store's at a time. It reaches the streams up to the log's heads when it was claimed; `wow.access` stamps the aggregate of a stream without attributes after them, from an import, when it reads it.
- **Its pace.** The walk is background work on the store the commands write to, so it takes a bounded share of it, whatever the load. After each aggregate it waits so that its stamping took at most `wow.access.walk-share` of the time (25% by default): an aggregate that took 3 ms is followed by a 9 ms wait. When the store is busy, from any node, the walk's own statements take longer and its pace slows with them. Once a second it reads whether the log store's writes are pressed, as the store's adaptive query bound reads them (a caller waiting for a connection, or appends well above their usual latency): while they are, the share halves, down to an eighth of `walk-share`, and it comes back by an eighth a second once they are not. It reads each history under the log store's query bulkhead, with the queries and the state rebuilds; a read the bulkhead refuses counts as pressure, and the walk reads that aggregate again a second later. It never stamps more than `wow.access.walk-rate` aggregates a second (500 by default). A store whose query bound is fixed (`wow.stores.<name>.query.max-concurrent` or `wow.query.max-concurrent`) gives no pressure to read: the walk keeps its share.
- **How long it takes.** At its share, a walk stamps about `walk-share` divided by the time one aggregate takes. On the benchmark's 4-core runner, with 200 commands a second and aggregates of 10 streams, the default share stamped about 150 aggregates a second on PostgreSQL and about 40 on MongoDB: 100,000 aggregates take about 11 and 40 minutes, 10 million about 19 hours and 3 days. While the writes press it slows to an eighth of that, and still ends. The streams it has not reached carry no current attributes: an events query under an access policy leaves them out until it does.
- **Tuning.** To walk faster in a maintenance window, raise `wow.access.walk-share`, up to `1`: the walk then runs as fast as the store and `walk-rate` let it, and still gives way while the writes press. Put it back afterwards: only an operator knows that the whole cluster is idle, which no node can see. The two stores differ. On PostgreSQL, in the same benchmark, a walk at full speed left the commands' p99 latency within its noise, so raising the share costs mostly CPU. On MongoDB, be careful: a walk at full speed about doubled the commands' median latency, which the default share avoids. Lower it when the commands' latency rises while a walk runs (`wow.access.walk.pending` reads 1).
- **On MongoDB, after declaring a new attribute.** Each aggregate's first commit afterwards has `wow.access` stamp its earlier streams, which `walk-share` does not bound, so write latency stays raised until the aggregates being written are stamped (about seven times the usual median in the benchmark's worst case, where every command reaches an aggregate not yet stamped): make such a change outside peak hours.
- **Resuming.** After each page the walk records how far it is in the log's registry, so a restart, or another node, goes on from there. A page holds as many aggregates as the walk stamps in about five seconds at its pace, at most `wow.access.walk-batch` (100 by default).
- **Watching it.** `wow.access.walk.pending` reads 1 for a type until its walk completes, and is not reported for a type with no walk yet; `wow.access.walk.walked` reads how many aggregates the walk has stamped, as the log's registry records it, the same on every node; `wow.access.walked` counts the aggregates this node walked, and `wow.access.stamped` the streams stamped, by why ([Metrics](../reference/observability#metrics)). No metric says how many are left, since the walk does not count the type's aggregates. To estimate it, take the type's aggregates from a `count` query of its snapshots (`{ "kind": "count", "filter": { "op": "match-all" } }`, [Querying](../data/querying)) or from the log itself (on PostgreSQL `SELECT count(*) FROM wow_log__<context>__<aggregate> WHERE version = 1`, on MongoDB `countDocuments({ version: 1 })` on that collection), subtract `wow.access.walk.walked`, and divide by the walk's rate of the last minutes (`rate(wow_access_walked_total[5m])` summed over the nodes) for the time it still takes. The registry holds each walk, its schema, heads, cursor, count and completion: on PostgreSQL `SELECT context, aggregate, access_walk FROM wow_log_aggregate`, on MongoDB the `access_walk` field of the `wow_log_aggregate` documents. The walking node logs `The access walk of <type> is completed: <n> aggregates stamped.` when it is done.

## The warehouse

The ClickHouse sink is a subscription too: its tables are derived from the log, and it keeps them exact when a commit reaches it twice ([ClickHouse](./clickhouse#delivered-once-read-once)).

A table the sink did not make in its present shape (its comment does not say the sink's format, it has other columns, or it lacks a computed column) fails the sink's writes, naming it ([Schema management](./clickhouse#schema-management)). It holds nothing the log does not, so drop it and reset the sink's subscription, which fills it again from the log.

## Parked aggregates

An aggregate a subscription parked is not lost: resuming it reads its stream again from the version it stopped at, and its handler sees every event it missed, in order ([Failures and Retries](../guide/failures#parked-aggregates)).
