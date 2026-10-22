---
title: Backup and Recovery
description: What a Wow service must back up, what it rebuilds instead, and how to restore it, from a lost read model to a lost database.
---

# Backup and Recovery

The log is the only fact a Wow service holds. Snapshots, projections' views, the warehouse and search indexes are all derived from it and can be rebuilt; what cannot be rebuilt is the log and the small amount of state that lives beside it in the same database. A backup plan protects those, and treats everything else as a rebuild.

## What to back up

One consistent backup of each service's database, on PostgreSQL or MongoDB, taken by the database's own tools. It holds:

| Data | Why it cannot be rebuilt |
| --- | --- |
| The log: every aggregate's committed streams, and the partitions' counters | It is the history itself |
| The reservations | Which aggregate holds each unique key |
| The timers | The commands scheduled for later |
| The failure ledger | The aggregates a subscription has parked, waiting for an operator |
| The checkpoints and leases | Where each subscription stands in the log, and which node owns which partition |
| The format records | Which format each table or collection was created with |

Take them together: a backup of the log without its checkpoints restores subscriptions that do not know where they were. [PostgreSQL](./postgresql#the-tables) and [MongoDB](./mongodb#the-collections) list the tables and collections.

The snapshots live in the same database, and a database backup takes them too: restored with the log, they match it. They are not what the backup protects, though, and a restore never needs them to be current.

## What not to back up

- **Projections' views** in a store of their own: their subscription writes them again from the log.
- **Snapshots** kept elsewhere, such as on [Elasticsearch](./elasticsearch): rebuilt from the log.
- **The warehouse**: the ClickHouse sink writes it again from the log ([ClickHouse](./clickhouse)).
- **Kafka topics** a service publishes: they carry copies of its commits for other contexts.

Rebuilding is slower than restoring, so a large read model may still deserve a backup of its own for speed. Its correctness never depends on one.

## Recover a read model

A projection's view, a snapshot table or a search index that is lost or wrong is rebuilt, not restored ([Rebuild and Replay](./replay)):

- a projection: deploy it under a new subscription name, which starts from the beginning of the log, and switch readers to its new view once it has caught up;
- snapshots: rebuild them from the log, for chosen aggregates or for a new store;
- the warehouse and the search index: start their sink subscription from the beginning.

The service keeps serving commands meanwhile.

## Restore a database

When a service's database is lost:

1. **Stop every node of the service.** No node may write to a database that is being restored.
2. **Restore the latest consistent backup** of the database.
3. **Start the nodes.** Subscriptions continue from the restored checkpoints, timers fire, parked aggregates wait in the ledger.
4. **Rebuild the read models kept outside the database.** A view or an index written from commits the restored log no longer has is ahead of it; rebuild it as above.

Commits made after the backup are lost with it. Callers that sent them got a success, and other contexts may already have read them from the published streams, so a restore to an earlier point is a decision with consequences beyond the service: restore to the latest point the database offers, using its continuous archiving where it has one, and tell the owners of the contexts that read the service's streams.

## Test the restore

A backup is proven by a restore: restore into an isolated environment, start a node on it, read the state of a few aggregates, and send them a command, which must append at the next version.
