---
title: Rebuild and Replay
description: Rebuild read models, snapshots and tags from the log, the only fact; a new subscription from the beginning, snapshot rebuilds by revision or by request, and tag backfills.
outline: deep
---

# Rebuild and Replay

The log is the only fact. Snapshots, read models, the warehouse and search indexes are derived from it, so any of them can be rebuilt from it, without a migration script and while the service runs. This page lists the ways, from the most common.

## A read model: a new subscription

A projection's position is its subscription's checkpoint, so a read model is rebuilt by giving the projection a subscription without one:

1. Deploy the changed projection under a new name (a new class, or `@Name`), writing to a new table or index. A projection without a checkpoint starts at the beginning of the log and fills its store with the history; `wow.subscriptions.[<processor.function>].start` can start it at an instant instead.
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
curl -X POST "http://<service>/<context>/snapshot/<aggregate>/wow.snapshot.rebuild" \
  -H "Content-Type: application/json" \
  -d '{"ids": ["alice-1", "alice-2"]}'
```

The route sends the system command `wow.snapshot.Rebuild` to the context's built-in rebuild aggregate, through admission like any command, so the authorizer decides who may send it. A built-in subscription then rewrites each named snapshot from the log, in every target store.

## A new snapshot store

Adding a snapshot target to an aggregate type (`wow.snapshot.stores`, or `wow.aggregates.[<context>.<aggregate>].snapshot.*`), an Elasticsearch index beside PostgreSQL, say, fills it from the beginning of the log by a subscription of its own, which stops once the target has caught up. Switching the primary target, the one queries read, moves no checkpoint.

## Tags

Streams appended before the log recorded tags, imported from 9.x for example, have none, and attribute-based access matches no aggregate without tags. `migrate-data backfill-tags` replays each aggregate and records the tags it had after each stream, then rewrites the Elasticsearch event documents indexed without them ([migrate-data CLI](../reference/migrate-data)).

## The warehouse

The ClickHouse sink is a subscription too: its tables are derived from the log, and it keeps them exact when a commit reaches it twice ([ClickHouse](./clickhouse#delivered-once-read-once)).

## Parked aggregates

An aggregate a subscription parked is not lost: resuming it reads its stream again from the version it stopped at, and its handler sees every event it missed, in order ([Failures and Retries](../guide/failures#parked-aggregates)).
