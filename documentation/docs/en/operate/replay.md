---
title: Rebuild and Replay
description: Rebuild read models and snapshots from the log, the only fact; a new subscription from the beginning, snapshot rebuilds by revision or by request, and the access attributes of streams written before them.
outline: deep
---

# Rebuild and Replay

The log is the only fact. Snapshots, read models, the warehouse and search indexes are derived from it, so any of them can be rebuilt from it, without a migration script and while the service runs. This page lists the ways, from the most common.

## A read model: a new subscription

A projection's position is its subscription's checkpoint, so a read model is rebuilt by giving the projection a subscription without one:

1. Deploy the changed projection under a new name (a new class, or `@Name`), writing to a new table or index. A projection without a checkpoint starts at the beginning of the log and fills its store with the history; `wow.subscriptions.[<context>.<processor.function>].start` can start it at an instant instead.
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

Streams appended before the log recorded them, by a 10.0 beta or by an import from 9.x, carry none, and neither do the snapshots a beta wrote: under an access policy, an events query leaves those streams out, and a snapshot query those snapshots, until they are stamped; an events query by a caller acting as an owner or in a space leaves those streams out too. The access walk of each aggregate type stamps them:

- **When it runs.** A node claims the walk of a type when the type's log records none, or records one of another access schema (the attributes the model declares, the type of each and whether it holds many) and no node of that schema is left. So the first node of a version that records the attributes walks the log a beta wrote, and a rolling deployment that declares a new attribute walks it once the last node of the previous version has stopped. A type the log holds no stream of yet has no walk, and the walk creates no collection or table for it: its first append creates them, and the next run claims its walk. After a migration, `migrate-data snapshot` stamps the imported history itself and records each type's walk as completed, so the nodes do not walk it ([The access attributes](../migrate/data#_7-the-access-attributes)).
- **What it does.** One node at a time pages the type's aggregates and stamps each one's whole history with the attributes of its current state, and rewrites its snapshot, at most `wow.access.walk-rate` aggregates a second (500 by default). A page of `wow.access.walk-batch` aggregates is a few writes, whatever its size: one read of their histories, their stamps together in statements of at most `wow.access.stamp-batch` streams, in the log and in the Elasticsearch events index, and one write of their snapshots. It reaches the streams up to the log's heads when it was claimed; `wow.access` stamps the aggregate of a stream without attributes after them, from a beta node still running or an import, when it reads it.
- **Resuming.** After each page of `wow.access.walk-batch` aggregates (100 by default) the walk records how far it is in the log's registry, so a restart, or another node, goes on from there.
- **Watching it.** `wow.access.walk.pending` reads 1 for a type until its walk completes, and is not reported for a type with no walk yet; `wow.access.walked` counts the aggregates walked, and `wow.access.stamped` the streams stamped, by why ([Metrics](../reference/observability#metrics)). The registry holds each walk, its schema, heads, cursor, count and completion: on PostgreSQL `SELECT context, aggregate, access_walk FROM wow_log_aggregate`, on MongoDB the `access_walk` field of the `wow_log_aggregate` documents. The walking node logs `The access walk of <type> is completed: <n> aggregates stamped.` when it is done.

A Sink table of a version that recorded tags is refused at startup, naming it; it holds nothing the log does not, so drop it and reset the Sink's subscription, which fills it again from the log.

## The warehouse

The ClickHouse sink is a subscription too: its tables are derived from the log, and it keeps them exact when a commit reaches it twice ([ClickHouse](./clickhouse#delivered-once-read-once)).

## Parked aggregates

An aggregate a subscription parked is not lost: resuming it reads its stream again from the version it stopped at, and its handler sees every event it missed, in order ([Failures and Retries](../guide/failures#parked-aggregates)).
