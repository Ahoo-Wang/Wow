---
title: Cut Over
description: Move a 9.x service's traffic to V10 with its history intact, offline by default or online for a large system, with the evidence a rehearsal must leave and the two ways back.
---

# Cut Over

The cut-over moves traffic from a 9.x service to its V10 service. 9.x and V10 never write the same data at once: there is no mixed-version protocol between them, so the cut-over is one write stop, and its length is what you choose between.

- **Offline, the default.** Stop 9.x's writes, import everything, compare, write the snapshots and the tags, start V10. It has the fewest steps and the least that can go wrong.
- **Online, the option for a large system** whose import does not fit a window it can stop for. 9.x keeps serving while the data is imported and followed, V10 waits on standby, and the write stop shrinks to a final pass.

[Migrate the Data](./data) describes each command of `migrate-data`; this page is the switch around them.

## Offline or online

Choose by the rehearsal's measured import time against the stop the business accepts, and record the choice. The rehearsal of the 9.x example and its view store, 50,000 orders and 12,500 views over four tenants, ran both paths on one runner:

| Path | Write stop, 9.x stopped to V10 serving | Where the time went |
| --- | --- | --- |
| Offline | 243.4 s | The full import 98.2 s, compare 23.5 s, snapshots 43.8 s, tags 35.4 s, start of both services 36.3 s |
| Online | 159.5 s | The final pass 15.2 s, compare 24.2 s, snapshots 46.5 s, tags 38.0 s, resume of both services 29.3 s |

The offline stop grows with the whole history, the online one with what 9.x wrote since the tail last read, plus the steps both share. A history that imports within the window you can stop for goes offline.

## Inventory before any write

Record, for every store and stream:

- the environment, the owner, the database, the bounded context, and the 9.x and V10 versions;
- the 9.x stores (the MongoDB event store, the `PrepareKey` collections, the compensation database) and the V10 targets (the log, the snapshots, the reservations, the failure ledger);
- the streams and aggregates per aggregate type, and the share with a 9.x snapshot;
- every writer and reader of the 9.x data and how each is stopped, and every external consumer of the 9.x event bus, with the V10 Kafka stream it moves to;
- the backup of each 9.x store, and a restore that was tested;
- the migration map, and the V10 Manifests it was checked against.

Do not start a write with an unknown writer, an unknown owner or an untested backup.

## What the rehearsal must leave

An end-to-end rehearsal on an isolated target, on production-sized data, leaves:

1. `check` passing on the production map;
2. an interrupted `import` resumed without duplicates, and a second run that writes nothing;
3. `compare` with every kind of difference explained and signed off;
4. the V10 services started on the target: a command on a migrated aggregate appends at the next version, reads and queries answer the migrated state, projections rebuilt, parked entries listed;
5. the timings: the whole import with `compare`, `snapshot` and `backfill-tags` for offline, or the final pass for online;
6. both rollbacks rehearsed, with the list of what cannot go back.

## The point of no easy return

Rolling back after V10 has written needs every stream V10 wrote to have a 9.x form. From the map and the V10 Manifests, list each V10 event type that has none, and give the list to whoever decides the cut-over:

- **An event past its map's `toRevision`.** A V10 class upgraded beyond the shape 9.x stored, often at the migration itself: a 9.x `OrderCreated` imported as revision 1 that V10 writes as revision 2. Every new order V10 creates blocks the rollback.
- **An event or an aggregate V10 added**, which no 9.x name maps to.

The first such write in production ends the rollback window. Either accept that the cut-over is one-way from that write, or keep those shapes and types out of the first V10 release until the window has closed. The rehearsal's own refusal shows it: once V10 created orders and views at revision 2, `export --dry-run` refused both types.

## The offline switch

1. **Announce the window, stop 9.x's writes.** Stop ingress to the 9.x services, drain their command queues, and stop the compensation service with them. Check that no 9.x writer is left.
2. **Take and verify the backup** of the 9.x stores.
3. **Import:** `check`, then `import`.
4. **Gate:** `compare` shows only the signed-off kinds of difference, and no unexplained unresolved key or execution. Otherwise stop here and restart 9.x: V10 has written nothing.
5. **Prepare the read side:** `snapshot`, and `backfill-tags` where ABAC reads the events model.
6. **Start the V10 services** and probe the critical paths: a command on a migrated aggregate, a read, a query, a subscription, the parked entries.
7. **Move the external consumers** of the 9.x bus to V10's published streams.
8. **Switch traffic to V10**, then scale it out. Keep 9.x stopped, not deleted.
9. **Observe** the agreed signals: errors and problem codes, latency, waits, subscription lag, parked aggregates and the consumers. The rollback decision is taken on them.

## The online switch

From the first import until the switch, 9.x serves and V10 waits: `tail` follows what 9.x writes, and every V10 node starts with `wow.node.standby=true`. A node on standby serves reads and queries of what the import writes, refuses every command with 503 `wow.node.standby`, and runs no subscription, parked retry, job or snapshot maintenance, so nothing V10 does can write. It reports ready, so the read side can be checked against production data before the switch.

1. **Announce the window, stop 9.x's writes**, as offline does.
2. **Take and verify the final backup.**
3. **Final pass.** `import --final` when the tail followed 9.x until it stopped: one tail pass from its checkpoint, looking back to catch commits that became visible late, then the reservations and the unresolved executions again. It refuses, writing nothing, when an import is incomplete or the tail has fallen behind; the final pass is then `import --restart`, as long as the whole history. Then `compare`, `snapshot` and `backfill-tags`.
4. **Gate**, as offline does. Otherwise go back to 9.x: the V10 nodes are still on standby and have written nothing.
5. **Probe the standby nodes** against the production target.
6. **Move the external consumers.**
7. **Resume every V10 node:** `POST /wow/node/resume` on each node's internal port, with `Wow-Service: <the node's service>` and `{"by": "<operator>", "reason": "<why>"}`. The node joins its groups, starts its work and then admits commands. The resume ends the write stop; `GET /wow/node/standby` shows each node's record of it.
8. **Switch traffic to V10**, then scale it out. Keep 9.x stopped.
9. **Observe**, as offline does.

## Roll back

Two paths, never confused:

- **V10 has not written yet:** stopped at the gate, still on standby, or switched back before the first V10 commit. Stop V10, switch traffic back, and restart 9.x on its untouched stores. Nothing to reconcile.
- **V10 has written.** 9.x does not have V10's streams, so restarting it alone loses them. Stop V10's writes, keep 9.x stopped, and run `migrate-data export --dry-run`. Per context it prints a verdict: switch back, export N streams, the window closed at a given time, or 9.x was written after the cut-over, which a person resolves first. Then `export` writes the streams V10 added back to the 9.x store by the map read backwards, resumable and idempotent, checks that every 9.x aggregate holds as many streams as V10 holds of its type, and 9.x starts again. In the rehearsal, 16,914 order and 16,815 view commands written by V10 were exported and checked in 52.9 s.

Only event streams go back: reservations V10 made, entries it parked and its projections' views have no 9.x form, and are reconciled by hand. 9.x's snapshots show the state before the exported streams until 9.x writes the instance again or regenerates them. Redeploying 9.x is not a rollback once V10 has written.

## Report

Record offline or online and why; the inventory; the rehearsal's evidence, each command with its exit code and counts; the signed-off kinds of difference; the types with no way back and the decision taken on them; the window's timings; and the switch's steps as done. Data imported, V10 serving, consumers moved and rollback rehearsed are four separate facts: none implies the next.
